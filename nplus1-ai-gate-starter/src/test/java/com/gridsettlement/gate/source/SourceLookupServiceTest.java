package com.gridsettlement.gate.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SourceLookupServiceTest {

    private final SourceLookupService lookupService = new SourceLookupService();

    @Test
    @DisplayName("Should locate Java source file using target/classes code source location")
    void shouldLocateSourceFileFromTargetClasses() {
        Path sourceFile = lookupService.resolveSourceFile(SourceLookupService.class);
        assertThat(sourceFile).isNotNull();
        assertThat(sourceFile.toString()).endsWith("SourceLookupService.java");
        assertThat(sourceFile).exists();
    }

    @Test
    @DisplayName("Should fall back to reflection when source file cannot be read")
    void shouldFallbackToReflection() {
        StackTraceElement frame = new StackTraceElement(
                DummyService.class.getName(),
                "process",
                "DummyService.java",
                9999 // line beyond end of file
        );

        String signature = lookupService.findRepositoryMethodSignature(frame);
        assertThat(signature).isNotNull();
        assertThat(signature).contains("DummyRepository");
    }

    @SuppressWarnings("unused")
    private static class DummyService {
        private DummyRepository dummyRepository;

        public void process() {
        }
    }

    @SuppressWarnings("unused")
    private interface DummyRepository {
        void findById(Long id);
    }
}
