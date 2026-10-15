package com.gridsettlement.gate.source;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves source files and repository method signatures across Maven reactor multi-module projects.
 * Navigates from target/classes to src/main/java and falls back to reflection when source files are unavailable.
 */
public class SourceLookupService {

    private static final Pattern REPO_CALL_PATTERN = Pattern.compile(
            "(\\b\\w+Repository\\w*)\\s*\\.\\s*(\\w+)\\s*\\("
    );

    /**
     * Finds the repository method signature called at the specified service stack frame.
     *
     * @param serviceFrame caller service stack frame
     * @return formatted signature (e.g. ContractDeclarationRepository.findConfirmedForParticipantSummary(LocalDate))
     *         or null if not determinable
     */
    public String findRepositoryMethodSignature(StackTraceElement serviceFrame) {
        if (serviceFrame == null) {
            return null;
        }

        Class<?> serviceClass = loadClass(serviceFrame.getClassName());
        if (serviceClass == null) {
            return serviceFrame.getClassName() + "." + serviceFrame.getMethodName();
        }

        // 1. Try reading the Java source code line at serviceFrame.getLineNumber()
        if (serviceFrame.getLineNumber() > 0) {
            Path sourceFile = resolveSourceFile(serviceClass);
            if (sourceFile != null && Files.isRegularFile(sourceFile)) {
                try {
                    List<String> lines = Files.readAllLines(sourceFile);
                    int lineIdx = serviceFrame.getLineNumber() - 1;
                    if (lineIdx >= 0 && lineIdx < lines.size()) {
                        String lineContent = lines.get(lineIdx).trim();
                        Matcher matcher = REPO_CALL_PATTERN.matcher(lineContent);
                        if (matcher.find()) {
                            String repoVarName = matcher.group(1);
                            String repoMethodName = matcher.group(2);
                            String signature = resolveSignatureFromField(serviceClass, repoVarName, repoMethodName);
                            if (signature != null) {
                                return signature;
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // Fall back to reflection
                }
            }
        }

        // 2. Fall back to Java reflection on serviceClass fields
        return resolveSignatureViaReflection(serviceClass, serviceFrame.getMethodName());
    }

    /**
     * Resolves the source .java file for a given class by navigating up from the code source location.
     *
     * @param clazz class to locate
     * @return Path to source file or null if not found
     */
    public Path resolveSourceFile(Class<?> clazz) {
        try {
            URL codeSource = clazz.getProtectionDomain().getCodeSource().getLocation();
            if (codeSource == null || !"file".equalsIgnoreCase(codeSource.getProtocol())) {
                return null;
            }

            URI uri = codeSource.toURI();
            Path path = Path.of(uri);
            String pathStr = path.toString().replace('\\', '/');

            // Look for target/classes or target/test-classes
            String targetClasses = "/target/classes";
            String targetTestClasses = "/target/test-classes";

            Path moduleRoot = null;
            String srcDir = "src/main/java";

            if (pathStr.contains(targetClasses)) {
                moduleRoot = Path.of(pathStr.substring(0, pathStr.indexOf(targetClasses)));
                srcDir = "src/main/java";
            } else if (pathStr.contains(targetTestClasses)) {
                moduleRoot = Path.of(pathStr.substring(0, pathStr.indexOf(targetTestClasses)));
                srcDir = "src/test/java";
            }

            if (moduleRoot != null) {
                String relativeJavaFile = clazz.getName().replace('.', '/') + ".java";
                // Strip inner class name if present (e.g. MyClass$Sub -> MyClass.java)
                if (relativeJavaFile.contains("$")) {
                    relativeJavaFile = relativeJavaFile.substring(0, relativeJavaFile.indexOf('$')) + ".java";
                }
                Path candidate = moduleRoot.resolve(srcDir).resolve(relativeJavaFile);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String resolveSignatureFromField(Class<?> serviceClass, String fieldName, String methodName) {
        Class<?> repoType = findFieldType(serviceClass, fieldName);
        if (repoType != null) {
            return formatMethodSignature(repoType, methodName);
        }
        return null;
    }

    private Class<?> findFieldType(Class<?> serviceClass, String fieldName) {
        Class<?> current = serviceClass;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equalsIgnoreCase(fieldName)) {
                    return field.getType();
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private String resolveSignatureViaReflection(Class<?> serviceClass, String serviceMethodName) {
        // Inspect fields of serviceClass to find candidate repository interfaces
        for (Field field : serviceClass.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (type.isInterface() && type.getSimpleName().endsWith("Repository")) {
                for (Method m : type.getDeclaredMethods()) {
                    // If method names match or service method calls a single repository method
                    if (m.getName().toLowerCase().contains("find") || m.getName().toLowerCase().contains("get")) {
                        return formatMethodSignature(type, m.getName());
                    }
                }
                return type.getSimpleName();
            }
        }
        return serviceClass.getSimpleName() + "." + serviceMethodName;
    }

    private String formatMethodSignature(Class<?> repoClass, String methodName) {
        for (Method m : repoClass.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                String paramTypes = Arrays.stream(m.getParameters())
                        .map(Parameter::getType)
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining(", "));
                return repoClass.getSimpleName() + "." + methodName + "(" + paramTypes + ")";
            }
        }
        return repoClass.getSimpleName() + "." + methodName;
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }
    }
}
