package com.example.dvely.agent.infrastructure.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentExecutionRegistryTest {

    @Test
    void snapshotIsEmptyWhenNothingIsRegistered() {
        AgentExecutionRegistry registry = new AgentExecutionRegistry();

        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void registerAddsAndUnregisterRemoves() {
        AgentExecutionRegistry registry = new AgentExecutionRegistry();

        registry.register("task-1");
        registry.register("task-2");
        assertThat(registry.snapshot()).containsExactlyInAnyOrder("task-1", "task-2");

        registry.unregister("task-1");
        assertThat(registry.snapshot()).containsExactly("task-2");
    }

    @Test
    void unregisteringAnUnknownTaskIdIsANoOp() {
        AgentExecutionRegistry registry = new AgentExecutionRegistry();

        registry.unregister("never-registered");

        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void snapshotIsAnImmutablePointInTimeCopy() {
        // Heartbeat must not see a registration/unregistration that happens after it already took
        // its snapshot — and callers must not be able to mutate the registry's internal state
        // through the returned set.
        AgentExecutionRegistry registry = new AgentExecutionRegistry();
        registry.register("task-1");

        Set<String> snapshot = registry.snapshot();
        registry.register("task-2");

        assertThat(snapshot).containsExactly("task-1");
        assertThat(registry.snapshot()).containsExactlyInAnyOrder("task-1", "task-2");
    }
}
