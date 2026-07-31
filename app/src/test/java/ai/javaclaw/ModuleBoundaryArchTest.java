package ai.javaclaw;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "ai.javaclaw", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchTest {

    private static final String[] CORE_PACKAGES = {
            "..agent..", "..tasks..", "..configuration..", "..files.."
    };

    private static final String[] PLUGIN_PACKAGES = {
            "..channels.telegram..", "..channels.discord..", "..tools.brave..", "..tools.playwright.."
    };

    private static final String[] PROVIDER_PACKAGES = {
            "..providers.openai..", "..providers.anthropic..", "..providers.ollama..", "..providers.google.."
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
                    .should().dependOnClassesThat().resideInAnyPackage(concat(PLUGIN_PACKAGES, PROVIDER_PACKAGES));

    private static String[] concat(String[] first, String[] second) {
        String[] result = new String[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}