package com.example.dvely.agent.infrastructure.codingagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The dev and prod profiles repeat the Claude Code argv as their own default, so a change to the
 * code default does not reach a deployment unless the yml moves with it. That is what happened in
 * #596: the code gained --bare and a permission mode while both profiles still ran plain claude -p,
 * and the local profile (no coding-agent block) hid it.
 *
 * Placeholders resolve against the yml alone, so this checks the written defaults, not whatever
 * the machine running the test has in its environment.
 */
class CodingAgentProfileDefaultsTest {

    @ParameterizedTest
    @ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
    void theProfileRunsClaudeCodeWithTheSameArgumentsAsTheCodeDefault(String file) throws IOException {
        CodingAgentProperties bound = bind(file);
        CodingAgentProperties code = new CodingAgentProperties();

        assertThat(bound.getClaude().getArgvPrefix()).isEqualTo(code.getClaude().getArgvPrefix());
        assertThat(bound.getClaude().getMaxBudgetUsd()).isEqualTo(code.getClaude().getMaxBudgetUsd());
    }

    private static CodingAgentProperties bind(String file) throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("qeploy.coding-agent", Bindable.ofInstance(new CodingAgentProperties())).get();
    }
}
