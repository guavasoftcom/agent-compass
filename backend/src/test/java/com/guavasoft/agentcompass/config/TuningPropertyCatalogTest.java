package com.guavasoft.agentcompass.config;

import org.junit.jupiter.api.Test;

import com.guavasoft.agentcompass.model.ConfigurationEntry;
import com.guavasoft.agentcompass.model.ConfigurationGroup;
import com.guavasoft.agentcompass.model.EffectiveConfiguration;
import com.guavasoft.agentcompass.model.SqlMirroring;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the hand-authored {@link TuningPropertyCatalog} against the one failure mode it has:
 * silently going out of date.
 *
 * <p>The catalog cannot be generated — the grouping, the descriptions, and above all the
 * {@link SqlMirroring} flag are facts about the migration history, not about the field. So instead
 * of deriving the catalog, this derives the *expectation*: reflection over
 * {@link TuningProperties}' declared fields, asserted against what the catalog claims to cover. A
 * newly added property therefore fails the build until someone classifies it, which is exactly when
 * the question "does this also need a migration?" is cheapest to answer.
 */
class TuningPropertyCatalogTest {

    private static final String MIGRATION_RESOURCE_PATTERN = "classpath:db/migration/%s__*.sql";

    private final TuningPropertyCatalog catalog = new TuningPropertyCatalog();

    @Test
    void everyTuningPropertyIsCatalogedExactlyOnce() {
        List<String> declaredFieldNames = declaredPropertyFieldNames();
        List<String> catalogedFieldNames = catalog.catalogedFieldNames();

        assertThat(catalogedFieldNames)
                .as("a property added to TuningProperties must be classified in TuningPropertyCatalog")
                .containsExactlyInAnyOrderElementsOf(declaredFieldNames)
                .doesNotHaveDuplicates();
    }

    @Test
    void propertyCountMatchesTheDeclaredFieldCount() {
        EffectiveConfiguration configuration = catalog.describe(new TuningProperties());

        assertThat(configuration.propertyCount()).isEqualTo(declaredPropertyFieldNames().size());
        assertThat(configuration.groups()).isNotEmpty();
        assertThat(configuration.groups()).allSatisfy(group -> {
            assertThat(group.name()).isNotBlank();
            assertThat(group.description()).isNotBlank();
            assertThat(group.entries()).isNotEmpty();
        });
    }

    @Test
    void defaultPropertiesReportNothingAsOverridden() {
        EffectiveConfiguration configuration = catalog.describe(new TuningProperties());

        assertThat(configuration.overriddenCount()).isZero();
        assertThat(allEntries(configuration)).noneMatch(ConfigurationEntry::overridden);
    }

    @Test
    void anOverriddenPropertyIsFlaggedAndRendersItsNewValue() {
        TuningProperties overridden = new TuningProperties();
        overridden.setToolEventName("custom_tool_event");

        EffectiveConfiguration configuration = catalog.describe(overridden);

        assertThat(configuration.overriddenCount()).isEqualTo(1);
        assertThat(entryFor(configuration, "tuning.tool-event-name"))
                .satisfies(entry -> {
                    assertThat(entry.value()).isEqualTo("custom_tool_event");
                    assertThat(entry.overridden()).isTrue();
                });
    }

    @Test
    void propertyNamesAreKebabCasedAndPrefixed() {
        EffectiveConfiguration configuration = catalog.describe(new TuningProperties());

        assertThat(allEntries(configuration))
                .allSatisfy(entry -> assertThat(entry.propertyName()).startsWith("tuning."))
                .allSatisfy(entry -> assertThat(entry.propertyName()).doesNotMatch(".*[A-Z].*"));
        assertThat(entryFor(configuration, "tuning.api-request-cost-attribute").value())
                .isEqualTo("cost_usd");
    }

    @Test
    void collectionAndMapValuesRenderAsReadableStrings() {
        EffectiveConfiguration configuration = catalog.describe(new TuningProperties());

        assertThat(entryFor(configuration, "tuning.error-event-names").value())
                .isEqualTo("api_error, internal_error, api_retries_exhausted");
        assertThat(entryFor(configuration, "tuning.bash-antipattern-replacements").value())
                .contains("cat=Read")
                .contains("find=Glob");
    }

    /**
     * The mirrored set is the whole point of the endpoint, so it is pinned rather than merely
     * counted — an accidental downgrade of one of these to NOT_MIRRORED would remove the only
     * runtime warning that overriding it silently breaks a page.
     */
    @Test
    void theSqlMirroredPropertiesArePinned() {
        EffectiveConfiguration configuration = catalog.describe(new TuningProperties());

        Map<SqlMirroring, List<String>> byMirroring = allEntries(configuration).stream()
                .collect(Collectors.groupingBy(
                        ConfigurationEntry::sqlMirroring,
                        Collectors.mapping(ConfigurationEntry::propertyName, Collectors.toList())));

        assertThat(byMirroring.get(SqlMirroring.MIRRORED)).containsExactlyInAnyOrder(
                "tuning.event-name-attribute",
                "tuning.tool-attribute",
                "tuning.api-request-event-name",
                "tuning.api-request-cost-attribute",
                "tuning.api-request-effort-attribute",
                "tuning.request-id-attribute",
                "tuning.llm-request-span-name",
                "tuning.error-event-names",
                "tuning.warn-event-names",
                "tuning.debug-event-names",
                "tuning.success-attribute",
                "tuning.decision-attribute",
                "tuning.status-attribute",
                "tuning.num-non-blocking-error-attribute",
                "tuning.tool-call-id-attribute",
                "tuning.tool-execution-span-name");
        assertThat(byMirroring.get(SqlMirroring.SHARED_LITERAL)).containsExactlyInAnyOrder(
                "tuning.tool-decision-event-name",
                "tuning.api-request-body-event-name",
                "tuning.hook-execution-event-name");
    }

    @Test
    void everyMirroredPropertyNamesAtLeastOneMigration() {
        EffectiveConfiguration configuration = catalog.describe(new TuningProperties());

        assertThat(allEntries(configuration))
                .filteredOn(entry -> entry.sqlMirroring() != SqlMirroring.NOT_MIRRORED)
                .allSatisfy(entry -> assertThat(entry.mirroredIn()).isNotEmpty());
        assertThat(allEntries(configuration))
                .filteredOn(entry -> entry.sqlMirroring() == SqlMirroring.NOT_MIRRORED)
                .allSatisfy(entry -> assertThat(entry.mirroredIn()).isEmpty());
    }

    /** A referenced migration that no longer exists means the catalog is pointing at nothing. */
    @Test
    void everyReferencedMigrationFileExists() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        for (String version : catalog.referencedMigrationVersions()) {
            Resource[] matches = resolver.getResources(MIGRATION_RESOURCE_PATTERN.formatted(version));
            assertThat(matches)
                    .as("migration %s referenced by TuningPropertyCatalog", version)
                    .isNotEmpty();
        }
    }

    @Test
    void everyEntryCarriesADescription() {
        EffectiveConfiguration configuration = catalog.describe(new TuningProperties());

        assertThat(allEntries(configuration))
                .allSatisfy(entry -> assertThat(entry.description()).isNotBlank());
    }

    private static List<String> declaredPropertyFieldNames() {
        return Stream.of(TuningProperties.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    private static List<ConfigurationEntry> allEntries(EffectiveConfiguration configuration) {
        return configuration.groups().stream()
                .map(ConfigurationGroup::entries)
                .flatMap(List::stream)
                .toList();
    }

    private static ConfigurationEntry entryFor(
            EffectiveConfiguration configuration, String propertyName) {
        Set<String> known = allEntries(configuration).stream()
                .map(ConfigurationEntry::propertyName)
                .collect(Collectors.toSet());
        assertThat(known).contains(propertyName);
        return allEntries(configuration).stream()
                .filter(entry -> entry.propertyName().equals(propertyName))
                .findFirst()
                .orElseThrow();
    }
}
