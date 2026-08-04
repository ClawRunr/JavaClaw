package ai.javaclaw;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "ai.javaclaw", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchTest {

    private static final String[] CORE_PACKAGES = {
            "ai.javaclaw.agent..", "ai.javaclaw.tasks..", "ai.javaclaw.configuration..", "ai.javaclaw.files.."
    };

    private static final String[] PLUGIN_PACKAGES = {
            "ai.javaclaw.channels.*..", "ai.javaclaw.tools.*.."
    };

    private static final String[] PROVIDER_PACKAGES = {
            "ai.javaclaw.providers.."
    };

    @ArchTest
    static final ArchRule channelPluginsAreIndependent =
            slices().matching("..channels.(*)..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule toolPluginsAreIndependent =
            slices().matching("..tools.(*)..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule llmProvidersAreIndependent =
            slices().matching("..providers.(*)..").should().notDependOnEachOther();

    @ArchTest
    static final ArchRule providersDoNotDependOnPlugins =
            noClasses().that().resideInAnyPackage(PROVIDER_PACKAGES)
                    .should().dependOnClassesThat().resideInAnyPackage(PLUGIN_PACKAGES);

    @ArchTest
    static final ArchRule pluginsDoNotDependOnProviders =
            noClasses().that().resideInAnyPackage(PLUGIN_PACKAGES)
                    .should().dependOnClassesThat().resideInAnyPackage(PROVIDER_PACKAGES);

    @ArchTest
    static final ArchRule coreDoesNotDependOnPluginsOrProviders =
            noClasses().that().resideInAnyPackage(CORE_PACKAGES)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            Stream.concat(Stream.of(PLUGIN_PACKAGES), Stream.of(PROVIDER_PACKAGES))
                                    .toArray(String[]::new));
}