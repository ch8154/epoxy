package com.airbnb.epoxy;

import android.support.annotation.LayoutRes;

import com.airbnb.epoxy.ClassToGenerateInfo.ConstructorInfo;
import com.airbnb.epoxy.ClassToGenerateInfo.MethodInfo;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.MethodSpec.Builder;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.AnnotationTypeMismatchException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import static com.airbnb.epoxy.ProcessorUtils.EPOXY_MODEL_TYPE;
import static com.airbnb.epoxy.ProcessorUtils.buildEpoxyException;
import static com.airbnb.epoxy.ProcessorUtils.getEpoxyObjectType;
import static com.airbnb.epoxy.ProcessorUtils.implementsMethod;
import static com.airbnb.epoxy.ProcessorUtils.isEpoxyModel;
import static com.airbnb.epoxy.ProcessorUtils.isEpoxyModelWithHolder;
import static com.squareup.javapoet.TypeName.BOOLEAN;
import static com.squareup.javapoet.TypeName.BYTE;
import static com.squareup.javapoet.TypeName.CHAR;
import static com.squareup.javapoet.TypeName.DOUBLE;
import static com.squareup.javapoet.TypeName.FLOAT;
import static com.squareup.javapoet.TypeName.INT;
import static com.squareup.javapoet.TypeName.LONG;
import static com.squareup.javapoet.TypeName.SHORT;
import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * Looks for {@link EpoxyAttribute} annotations and generates a subclass for all classes that have
 * those attributes. The generated subclass includes setters, getters, equals, and hashcode for the
 * given field. Any constructors on the original class are duplicated. Abstract classes are ignored
 * since generated classes would have to be abstract in order to guarantee they compile, and that
 * reduces their usefulness and doesn't make as much sense to support.
 */
@AutoService(Processor.class)
public class EpoxyProcessor extends AbstractProcessor {

  private static final String CREATE_NEW_HOLDER_METHOD_NAME = "createNewHolder";
  private static final String GET_DEFAULT_LAYOUT_METHOD_NAME = "getDefaultLayout";

  private Filer filer;
  private Messager messager;
  private Elements elementUtils;
  private Types typeUtils;

  private ResourceProcessor resourceProcessor;
  private ConfigManager configManager;
  private final List<Exception> loggedExceptions = new ArrayList<>();

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    filer = processingEnv.getFiler();
    messager = processingEnv.getMessager();
    elementUtils = processingEnv.getElementUtils();
    typeUtils = processingEnv.getTypeUtils();

    resourceProcessor = new ResourceProcessor(processingEnv, elementUtils, typeUtils);
    configManager = new ConfigManager(elementUtils);
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    Set<String> types = new LinkedHashSet<>();
    for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
      types.add(annotation.getCanonicalName());
    }
    return types;
  }

  static Set<Class<? extends Annotation>> getSupportedAnnotations() {
    Set<Class<? extends Annotation>> annotations = new LinkedHashSet<>();

    annotations.add(EpoxyModelClass.class);
    annotations.add(EpoxyAttribute.class);
    annotations.add(PackageEpoxyConfig.class);

    return annotations;
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    logErrors(configManager.processConfigurations(roundEnv));
    resourceProcessor.processorResources(roundEnv);

    LinkedHashMap<TypeElement, ClassToGenerateInfo> modelClassMap = new LinkedHashMap<>();

    for (Element attribute : roundEnv.getElementsAnnotatedWith(EpoxyAttribute.class)) {
      try {
        addAttributeToGeneratedClass(attribute, modelClassMap);
      } catch (Exception e) {
        logError(e);
      }
    }

    for (Element clazz : roundEnv.getElementsAnnotatedWith(EpoxyModelClass.class)) {
      try {
        getOrCreateTargetClass(modelClassMap, (TypeElement) clazz);
      } catch (Exception e) {
        logError(e);
      }
    }

    try {
      addAttributesFromOtherModules(modelClassMap);
    } catch (Exception e) {
      logError(e);
    }

    try {
      updateClassesForInheritance(modelClassMap);
    } catch (Exception e) {
      logError(e);
    }

    for (Entry<TypeElement, ClassToGenerateInfo> modelEntry : modelClassMap.entrySet()) {
      try {
        generateClassForModel(modelEntry.getValue());
      } catch (Exception e) {
        logError(e);
      }
    }

    validateAttributesImplementHashCode(modelClassMap.values());

    if (roundEnv.processingOver()) {

      // We wait until the very end to log errors so that all the generated classes are still
      // created.
      // Otherwise the compiler error output is clogged with lots of errors from the generated
      // classes  not existing, which makes it hard to see the actual errors.
      for (Exception loggedException : loggedExceptions) {
        messager.printMessage(Diagnostic.Kind.ERROR, loggedException.toString());
      }
    }

    // Let any other annotation processors use our annotations if they want to
    return false;
  }

  private void validateAttributesImplementHashCode(
      Collection<ClassToGenerateInfo> generatedClasses) {
    HashCodeValidator hashCodeValidator = new HashCodeValidator(typeUtils);

    for (ClassToGenerateInfo generatedClass : generatedClasses) {
      for (AttributeInfo attributeInfo : generatedClass.getAttributeInfo()) {
        if (configManager.requiresHashCode(attributeInfo) && attributeInfo.useInHash()) {
          try {
            hashCodeValidator.validate(attributeInfo);
          } catch (EpoxyProcessorException e) {
            logError(e);
          }
        }
      }
    }
  }

  private void addAttributeToGeneratedClass(Element attribute,
      Map<TypeElement, ClassToGenerateInfo> modelClassMap) {
    TypeElement classElement = (TypeElement) attribute.getEnclosingElement();
    ClassToGenerateInfo helperClass = getOrCreateTargetClass(modelClassMap, classElement);
    helperClass.addAttribute(buildAttributeInfo(attribute));
  }

  private AttributeInfo buildAttributeInfo(Element attribute) {
    validateAccessibleViaGeneratedCode(attribute);
    return new AttributeInfo(attribute, typeUtils);
  }

  private void validateAccessibleViaGeneratedCode(Element attribute) {
    TypeElement enclosingElement = (TypeElement) attribute.getEnclosingElement();

    // Verify method modifiers.
    Set<Modifier> modifiers = attribute.getModifiers();
    if (modifiers.contains(PRIVATE) || modifiers.contains(STATIC)) {
      logError(
          "%s annotations must not be on private or static fields. (class: %s, field: %s)",
          EpoxyAttribute.class.getSimpleName(),
          enclosingElement.getSimpleName(), attribute.getSimpleName());
    }

    // Nested classes must be static
    if (enclosingElement.getNestingKind().isNested()) {
      if (!enclosingElement.getModifiers().contains(STATIC)) {
        logError(
            "Nested classes with %s annotations must be static. (class: %s, field: %s)",
            EpoxyAttribute.class.getSimpleName(),
            enclosingElement.getSimpleName(), attribute.getSimpleName());
      }
    }

    // Verify containing type.
    if (enclosingElement.getKind() != CLASS) {
      logError("%s annotations may only be contained in classes. (class: %s, field: %s)",
          EpoxyAttribute.class.getSimpleName(),
          enclosingElement.getSimpleName(), attribute.getSimpleName());
    }

    // Verify containing class visibility is not private.
    if (enclosingElement.getModifiers().contains(PRIVATE)) {
      logError("%s annotations may not be contained in private classes. (class: %s, field: %s)",
          EpoxyAttribute.class.getSimpleName(),
          enclosingElement.getSimpleName(), attribute.getSimpleName());
    }
  }

  private ClassToGenerateInfo getOrCreateTargetClass(
      Map<TypeElement, ClassToGenerateInfo> modelClassMap, TypeElement classElement) {

    ClassToGenerateInfo classToGenerateInfo = modelClassMap.get(classElement);

    boolean isFinal = classElement.getModifiers().contains(Modifier.FINAL);
    if (isFinal) {
      logError("Class with %s annotations cannot be final: %s",
          EpoxyAttribute.class.getSimpleName(), classElement.getSimpleName());
    }

    if (!isEpoxyModel(classElement.asType())) {
      logError("Class with %s annotations must extend %s (%s)",
          EpoxyAttribute.class.getSimpleName(), EPOXY_MODEL_TYPE,
          classElement.getSimpleName());
    }

    if (configManager.requiresAbstractModels(classElement)
        && !classElement.getModifiers().contains(ABSTRACT)) {
      logError("Epoxy model class must be abstract (%s)", classElement.getSimpleName());
    }

    if (classToGenerateInfo == null) {
      classToGenerateInfo = new ClassToGenerateInfo(typeUtils, elementUtils, classElement);
      modelClassMap.put(classElement, classToGenerateInfo);
    }

    return classToGenerateInfo;
  }

  /**
   * Looks for attributes on super classes that weren't included in this processor's coverage. Super
   * classes are already found if they are in the same module since the processor will pick them up
   * with the rest of the annotations.
   */
  private void addAttributesFromOtherModules(Map<TypeElement, ClassToGenerateInfo> modelClassMap) {
    // Copy the entries in the original map so we can add new entries to the map while we iterate
    // through the old entries
    Set<Entry<TypeElement, ClassToGenerateInfo>> originalEntries =
        new HashSet<>(modelClassMap.entrySet());

    for (Entry<TypeElement, ClassToGenerateInfo> entry : originalEntries) {
      TypeElement currentEpoxyModel = entry.getKey();
      TypeMirror superclassType = currentEpoxyModel.getSuperclass();
      ClassToGenerateInfo classToGenerateInfo = entry.getValue();

      while (isEpoxyModel(superclassType)) {
        TypeElement superclassEpoxyModel = (TypeElement) typeUtils.asElement(superclassType);

        if (!modelClassMap.keySet().contains(superclassEpoxyModel)) {
          for (Element element : superclassEpoxyModel.getEnclosedElements()) {
            if (element.getAnnotation(EpoxyAttribute.class) != null) {
              AttributeInfo attributeInfo = buildAttributeInfo(element);
              if (!belongToTheSamePackage(currentEpoxyModel, superclassEpoxyModel)
                  && attributeInfo.isPackagePrivate()) {
                // We can't inherit a package private attribute if we're not in the same package
                continue;
              }

              // We add just the attribute info to the class in our module. We do NOT want to
              // generate a class for the super class EpoxyModel in the other module since one
              // will be created when that module is processed. If we make one as well there will
              // be a duplicate (causes proguard errors and is just wrong).
              classToGenerateInfo.addAttribute(attributeInfo);
            }
          }
        }

        superclassType = superclassEpoxyModel.getSuperclass();
      }
    }
  }

  /**
   * Check each model for super classes that also have attributes. For each super class with
   * attributes we add those attributes to the attributes of the generated class, so that a
   * generated class contains all the attributes of its super classes combined.
   * <p>
   * One caveat is that if a sub class is in a different package than its super class we can't
   * include attributes that are package private, otherwise the generated class won't compile.
   */
  private void updateClassesForInheritance(
      Map<TypeElement, ClassToGenerateInfo> helperClassMap) {
    for (Entry<TypeElement, ClassToGenerateInfo> entry : helperClassMap.entrySet()) {
      TypeElement thisClass = entry.getKey();

      Map<TypeElement, ClassToGenerateInfo> otherClasses = new LinkedHashMap<>(helperClassMap);
      otherClasses.remove(thisClass);

      for (Entry<TypeElement, ClassToGenerateInfo> otherEntry : otherClasses.entrySet()) {
        TypeElement otherClass = otherEntry.getKey();

        if (!isSubtype(thisClass, otherClass)) {
          continue;
        }

        Set<AttributeInfo> otherAttributes = otherEntry.getValue().getAttributeInfo();

        if (belongToTheSamePackage(thisClass, otherClass)) {
          entry.getValue().addAttributes(otherAttributes);
        } else {
          for (AttributeInfo attribute : otherAttributes) {
            if (!attribute.isPackagePrivate()) {
              entry.getValue().addAttribute(attribute);
            }
          }
        }
      }
    }
  }

  /**
   * Checks if two classes belong to the same package
   */
  private boolean belongToTheSamePackage(TypeElement class1, TypeElement class2) {
    Name package1 = elementUtils.getPackageOf(class1).getQualifiedName();
    Name package2 = elementUtils.getPackageOf(class2).getQualifiedName();
    return package1.equals(package2);
  }

  private boolean isSubtype(TypeElement e1, TypeElement e2) {
    return isSubtype(e1.asType(), e2.asType());
  }

  private boolean isSubtype(TypeMirror e1, TypeMirror e2) {
    // We use erasure so that EpoxyModelA is considered a subtype of EpoxyModel<T extends View>
    return typeUtils.isSubtype(e1, typeUtils.erasure(e2));
  }

  private void generateClassForModel(ClassToGenerateInfo info)
      throws IOException {
    if (!info.shouldGenerateSubClass()) {
      return;
    }

    TypeSpec generatedClass = TypeSpec.classBuilder(info.getGeneratedName())
        .addJavadoc("Generated file. Do not modify!")
        .addModifiers(Modifier.PUBLIC)
        .superclass(info.getOriginalClassName())
        .addTypeVariables(info.getTypeVariables())
        .addMethods(generateConstructors(info))
        .addMethods(generateSettersAndGetters(info))
        .addMethods(generateMethodsReturningClassType(info))
        .addMethods(generateDefaultMethodImplementations(info))
        .addMethod(generateReset(info))
        .addMethod(generateEquals(info))
        .addMethod(generateHashCode(info))
        .addMethod(generateToString(info))
        .build();

    JavaFile.builder(info.getGeneratedName().packageName(), generatedClass)
        .build()
        .writeTo(filer);
  }

  /** Include any constructors that are in the super class. */
  private Iterable<MethodSpec> generateConstructors(ClassToGenerateInfo info) {
    List<MethodSpec> constructors = new ArrayList<>(info.getConstructors().size());

    for (ConstructorInfo constructorInfo : info.getConstructors()) {
      Builder builder = MethodSpec.constructorBuilder()
          .addModifiers(constructorInfo.modifiers)
          .addParameters(constructorInfo.params)
          .varargs(constructorInfo.varargs);

      StringBuilder statementBuilder = new StringBuilder("super(");
      generateParams(statementBuilder, constructorInfo.params);

      constructors.add(builder
          .addStatement(statementBuilder.toString())
          .build());
    }

    return constructors;
  }

  private Iterable<MethodSpec> generateMethodsReturningClassType(ClassToGenerateInfo info) {
    List<MethodSpec> methods = new ArrayList<>(info.getMethodsReturningClassType().size());

    for (MethodInfo methodInfo : info.getMethodsReturningClassType()) {
      Builder builder = MethodSpec.methodBuilder(methodInfo.name)
          .addModifiers(methodInfo.modifiers)
          .addParameters(methodInfo.params)
          .addAnnotation(Override.class)
          .varargs(methodInfo.varargs)
          .returns(info.getParameterizedGeneratedName());

      StringBuilder statementBuilder = new StringBuilder(String.format("super.%s(",
          methodInfo.name));
      generateParams(statementBuilder, methodInfo.params);

      methods.add(builder
          .addStatement(statementBuilder.toString())
          .addStatement("return this")
          .build());
    }

    return methods;
  }

  /**
   * Generates default implementations of certain model methods if the model is abstract and doesn't
   * implement them.
   */
  private Iterable<MethodSpec> generateDefaultMethodImplementations(ClassToGenerateInfo info) {

    List<MethodSpec> methods = new ArrayList<>();
    TypeElement originalClassElement = info.getOriginalClassElement();

    addCreateHolderMethodIfNeeded(originalClassElement, methods);
    addDefaultLayoutMethodIfNeeded(originalClassElement, methods);

    return methods;
  }

  /**
   * If the model is a holder and doesn't implement the "createNewHolder" method we can generate a
   * default implementation by getting the class type and creating a new instance of it.
   */
  private void addCreateHolderMethodIfNeeded(TypeElement originalClassElement,
      List<MethodSpec> methods) {

    if (!isEpoxyModelWithHolder(originalClassElement)) {
      return;
    }

    MethodSpec createHolderMethod = MethodSpec.methodBuilder(CREATE_NEW_HOLDER_METHOD_NAME)
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PROTECTED)
        .build();

    if (implementsMethod(originalClassElement, createHolderMethod, typeUtils)) {
      return;
    }

    TypeMirror epoxyObjectType = getEpoxyObjectType(originalClassElement, typeUtils);
    if (epoxyObjectType == null) {
      logError("Return type for createNewHolder method could not be found. (class: %s)",
          originalClassElement.getSimpleName());
      return;
    }

    createHolderMethod = createHolderMethod.toBuilder()
        .returns(TypeName.get(epoxyObjectType))
        .addStatement("return new $T()", epoxyObjectType)
        .build();

    methods.add(createHolderMethod);
  }

  /**
   * If there is no existing implementation of getDefaultLayout we can generate an implementation.
   * This relies on a layout res being set in the @EpoxyModelClass annotation.
   */
  private void addDefaultLayoutMethodIfNeeded(TypeElement originalClassElement,
      List<MethodSpec> methods) {

    MethodSpec getDefaultLayoutMethod = MethodSpec.methodBuilder(GET_DEFAULT_LAYOUT_METHOD_NAME)
        .addAnnotation(Override.class)
        .addAnnotation(LayoutRes.class)
        .addModifiers(Modifier.PROTECTED)
        .returns(TypeName.INT)
        .build();

    if (implementsMethod(originalClassElement, getDefaultLayoutMethod, typeUtils)) {
      return;
    }

    EpoxyModelClass annotation = findClassAnnotationWithLayout(originalClassElement);
    if (annotation == null) {
      logError("Model must use %s annotation if it does not implement %s. (class: %s)",
          EpoxyModelClass.class,
          GET_DEFAULT_LAYOUT_METHOD_NAME,
          originalClassElement.getSimpleName());
      return;
    }

    int layoutRes;
    try {
      layoutRes = annotation.layout();
    } catch (AnnotationTypeMismatchException e) {
      logError("Invalid layout value in %s annotation. (class: %s). %s: %s",
          EpoxyModelClass.class,
          originalClassElement.getSimpleName(),
          e.getClass().getSimpleName(),
          e.getMessage());
      return;
    }

    if (layoutRes == 0) {
      logError("Model must specify a valid layout resource in the %s annotation. (class: %s)",
          EpoxyModelClass.class,
          originalClassElement.getSimpleName());
      return;
    }

    AndroidResource layoutResource = resourceProcessor.getResourceForValue(layoutRes);
    getDefaultLayoutMethod = getDefaultLayoutMethod.toBuilder()
        .addStatement("return $L", layoutResource.code)
        .build();

    methods.add(getDefaultLayoutMethod);
  }

  /**
   * Looks for {@link EpoxyModelClass} annotation in the original class and his parents.
   */
  private EpoxyModelClass findClassAnnotationWithLayout(TypeElement classElement) {
    if (!isEpoxyModel(classElement)) {
      return null;
    }

    EpoxyModelClass annotation = classElement.getAnnotation(EpoxyModelClass.class);
    if (annotation == null) {
      return null;
    }

    try {
      int layoutRes = annotation.layout();
      if (layoutRes != 0) {
        return annotation;
      }
    } catch (AnnotationTypeMismatchException e) {
      logError("Invalid layout value in %s annotation. (class: %s). %s: %s",
          EpoxyModelClass.class,
          classElement.getSimpleName(),
          e.getClass().getSimpleName(),
          e.getMessage());
      return null;
    }

    TypeElement superclassElement = (TypeElement) typeUtils.asElement(classElement.getSuperclass());
    EpoxyModelClass annotationOnSuperClass = findClassAnnotationWithLayout(superclassElement);

    // Return the last annotation value we have so the proper error can be thrown if needed
    return annotationOnSuperClass != null ? annotationOnSuperClass : annotation;
  }

  private void generateParams(StringBuilder statementBuilder, List<ParameterSpec> params) {
    boolean first = true;
    for (ParameterSpec param : params) {
      if (!first) {
        statementBuilder.append(", ");
      }
      first = false;
      statementBuilder.append(param.name);
    }
    statementBuilder.append(")");
  }

  private List<MethodSpec> generateSettersAndGetters(ClassToGenerateInfo helperClass) {
    List<MethodSpec> methods = new ArrayList<>();

    for (AttributeInfo data : helperClass.getAttributeInfo()) {
      if (data.generateSetter() && !data.hasFinalModifier()) {
        methods.add(generateSetter(helperClass, data));
      }
      methods.add(generateGetter(data));
    }

    return methods;
  }

  private MethodSpec generateEquals(ClassToGenerateInfo helperClass) {
    Builder builder = MethodSpec.methodBuilder("equals")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(boolean.class)
        .addParameter(Object.class, "o")
        .beginControlFlow("if (o == this)")
        .addStatement("return true")
        .endControlFlow()
        .beginControlFlow("if (!(o instanceof $T))", helperClass.getGeneratedName())
        .addStatement("return false")
        .endControlFlow()
        .beginControlFlow("if (!super.equals(o))")
        .addStatement("return false")
        .endControlFlow()
        .addStatement("$T that = ($T) o", helperClass.getGeneratedName(),
            helperClass.getGeneratedName());

    for (AttributeInfo attributeInfo : helperClass.getAttributeInfo()) {
      TypeName type = attributeInfo.getType();

      if (!attributeInfo.useInHash() && type.isPrimitive()) {
        continue;
      }

      String name = attributeInfo.getName();

      if (attributeInfo.useInHash()) {
        if (type == FLOAT) {
          builder.beginControlFlow("if (Float.compare(that.$L, $L) != 0)", name, name)
              .addStatement("return false")
              .endControlFlow();
        } else if (type == DOUBLE) {
          builder.beginControlFlow("if (Double.compare(that.$L, $L) != 0)", name, name)
              .addStatement("return false")
              .endControlFlow();
        } else if (type.isPrimitive()) {
          builder.beginControlFlow("if ($L != that.$L)", name, name)
              .addStatement("return false")
              .endControlFlow();
        } else if (type instanceof ArrayTypeName) {
          builder.beginControlFlow("if (!$T.equals($L, that.$L))", TypeName.get(Arrays.class), name,
              name)
              .addStatement("return false")
              .endControlFlow();
        } else {
          builder
              .beginControlFlow("if ($L != null ? !$L.equals(that.$L) : that.$L != null)",
                  name, name, name, name)
              .addStatement("return false")
              .endControlFlow();
        }
      } else {
        builder.beginControlFlow("if ($L != null && that.$L == null"
                + " || $L == null && that.$L != null)",
            name, name, name, name)
            .addStatement("return false")
            .endControlFlow();
      }
    }

    return builder
        .addStatement("return true")
        .build();
  }

  private MethodSpec generateHashCode(ClassToGenerateInfo helperClass) {
    Builder builder = MethodSpec.methodBuilder("hashCode")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(int.class)
        .addStatement("int result = super.hashCode()");

    for (AttributeInfo attributeInfo : helperClass.getAttributeInfo()) {
      if (!attributeInfo.useInHash()) {
        continue;
      }
      if (attributeInfo.getType() == DOUBLE) {
        builder.addStatement("long temp");
        break;
      }
    }

    for (AttributeInfo attributeInfo : helperClass.getAttributeInfo()) {
      TypeName type = attributeInfo.getType();

      if (!attributeInfo.useInHash() && type.isPrimitive()) {
        continue;
      }

      String name = attributeInfo.getName();

      if (attributeInfo.useInHash()) {
        if ((type == BYTE) || (type == CHAR) || (type == SHORT) || (type == INT)) {
          builder.addStatement("result = 31 * result + $L", name);
        } else if (type == LONG) {
          builder.addStatement("result = 31 * result + (int) ($L ^ ($L >>> 32))", name, name);
        } else if (type == FLOAT) {
          builder.addStatement("result = 31 * result + ($L != +0.0f "
              + "? Float.floatToIntBits($L) : 0)", name, name);
        } else if (type == DOUBLE) {
          builder.addStatement("temp = Double.doubleToLongBits($L)", name)
              .addStatement("result = 31 * result + (int) (temp ^ (temp >>> 32))");
        } else if (type == BOOLEAN) {
          builder.addStatement("result = 31 * result + ($L ? 1 : 0)", name);
        } else if (type instanceof ArrayTypeName) {
          builder.addStatement("result = 31 * result + Arrays.hashCode($L)", name);
        } else {
          builder.addStatement("result = 31 * result + ($L != null ? $L.hashCode() : 0)", name,
              name);
        }
      } else {
        builder.addStatement("result = 31 * result + ($L != null ? 1 : 0)", name);
      }
    }

    return builder
        .addStatement("return result")
        .build();
  }

  private MethodSpec generateToString(ClassToGenerateInfo helperClass) {
    Builder builder = MethodSpec.methodBuilder("toString")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(String.class);

    StringBuilder sb = new StringBuilder();
    sb.append(String.format("\"%s{\" +\n", helperClass.getGeneratedName().simpleName()));

    boolean first = true;
    for (AttributeInfo attributeInfo : helperClass.getAttributeInfo()) {
      String attributeName = attributeInfo.getName();
      if (first) {
        sb.append(String.format("\"%s=\" + %s +\n", attributeName, attributeName));
        first = false;
      } else {
        sb.append(String.format("\", %s=\" + %s +\n", attributeName, attributeName));
      }
    }

    sb.append("\"}\" + super.toString()");

    return builder
        .addStatement("return $L", sb.toString())
        .build();
  }

  private MethodSpec generateGetter(AttributeInfo data) {
    return MethodSpec.methodBuilder(data.getName())
        .addModifiers(Modifier.PUBLIC)
        .returns(data.getType())
        .addAnnotations(data.getGetterAnnotations())
        .addStatement("return $L", data.getName())
        .build();
  }

  private MethodSpec generateSetter(ClassToGenerateInfo helperClass, AttributeInfo data) {
    String attributeName = data.getName();
    Builder builder = MethodSpec.methodBuilder(attributeName)
        .addModifiers(Modifier.PUBLIC)
        .returns(helperClass.getParameterizedGeneratedName())
        .addParameter(ParameterSpec.builder(data.getType(), attributeName)
            .addAnnotations(data.getSetterAnnotations()).build())
        .addStatement("this.$L = $L", attributeName, attributeName);

    if (data.hasSuperSetterMethod()) {
      builder.addStatement("super.$L($L)", attributeName, attributeName);
    }

    return builder
        .addStatement("return this")
        .build();
  }

  private MethodSpec generateReset(ClassToGenerateInfo helperClass) {
    Builder builder = MethodSpec.methodBuilder("reset")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(helperClass.getParameterizedGeneratedName());

    for (AttributeInfo attributeInfo : helperClass.getAttributeInfo()) {
      if (!attributeInfo.hasFinalModifier()) {
        builder.addStatement("this.$L = $L", attributeInfo.getName(),
            getDefaultValue(attributeInfo.getType()));
      }
    }

    return builder
        .addStatement("super.reset()")
        .addStatement("return this")
        .build();
  }

  private void logErrors(List<Exception> exceptions) {
    for (Exception exception : exceptions) {
      logError(exception);
    }
  }

  private void logError(String msg, Object... args) {
    logError(buildEpoxyException(msg, args));
  }

  /**
   * Errors are logged and saved until after classes are generating. Otherwise if we throw
   * immediately the models are not generated which leads to lots of other compiler errors which
   * mask the actual issues.
   */
  private void logError(Exception e) {
    loggedExceptions.add(e);
  }

  private static String getDefaultValue(TypeName attributeType) {
    if (attributeType == BOOLEAN) {
      return "false";
    } else if (attributeType == BYTE || attributeType == CHAR || attributeType == SHORT
        || attributeType == INT) {
      return "0";
    } else if (attributeType == LONG) {
      return "0L";
    } else if (attributeType == FLOAT) {
      return "0.0f";
    } else if (attributeType == DOUBLE) {
      return "0.0d";
    } else {
      return "null";
    }
  }
}
