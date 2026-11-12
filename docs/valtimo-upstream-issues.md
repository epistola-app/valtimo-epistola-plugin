# Valtimo: issues to raise upstream

Defects and gaps in Valtimo found while building this plugin, written up so they can be reported to
Ritense. Each entry says what happens, where, what it costs the plugin, and what would fix it. The
plugin deliberately does **not** work around these where a workaround would mean reaching into the
engine: it lives with the gap and records it here.

## A plugin action's exception reaches the engine wrapped, so every incident says the same thing

**Seen in** Valtimo 13.50.0 (checked in the `com.ritense.valtimo:plugin` jar), with Operaton.

**What happens.** When a `@PluginAction` linked to a service task throws, the incident the engine
raises reads _"Failed to invoke event listener method"_, whatever the action threw. The action's own
message (for this plugin, what is missing and how to fix it, such as _"Template 'besluit' is not in
catalog 'local/demo/brieven' in release 2.0.0"_) is only in the incident's stack trace, one click
deeper in Cockpit and absent from the incident list, the REST API's incident message and any alert
built on them. This is not specific to Epistola: every plugin's action failures read the same.

**Why.** Two layers wrap the exception and neither unwraps it:

1. `PluginService.invoke(DelegateExecution, PluginProcessLink)` (and the `DelegateTask` overload)
   calls the action through `java.lang.reflect.Method.invoke`. That throws the action's exception
   as an `InvocationTargetException`, which `invoke` lets propagate. Kotlin does not make it declare
   or handle the checked exception, so nothing in the compiler points at it.
2. `ProcessLinkServiceTaskStartListener` is a Spring `@EventListener`. Spring's
   `ApplicationListenerMethodAdapter` rethrows a listener's `RuntimeException` as is, but a checked
   exception (here the `InvocationTargetException`) becomes an
   `UndeclaredThrowableException` with the message _"Failed to invoke event listener method"_.

The engine records a failed job with `exception.getMessage()` of the outermost exception, so the
incident is `UndeclaredThrowableException(InvocationTargetException(the action's exception))`, by
its first message.

**Suggested fix (one place, Valtimo-side).** In both `PluginService.invoke` overloads, unwrap the
reflective call:

```kotlin
try {
    method.invoke(instance, *args)
} catch (e: InvocationTargetException) {
    throw e.targetException
}
```

The action's `RuntimeException` then passes through Spring's adapter unchanged, and the incident
carries the action's message. Nothing else changes: the stack trace still shows the full path.

**What it costs the plugin until then.** ADR 0009 (third addendum) makes a refused generation an
incident that says what is missing, so someone can fix the case or the configuration and retry. With
this defect the diagnosis is still there, in the stack trace, but the summary everyone sees first is
generic. The plugin briefly replaced the engine's `FailedJobCommandFactory` to unwrap its own
actions' failures; that was removed again as too much engine reach for a defect that belongs in
Valtimo. `EpistolaRefusalIncidentIntegrationTest` reproduces Valtimo's two wrappers in its delegate and pins
both halves (the wrapper on the incident, the diagnosis in the stack trace). It imitates Valtimo
rather than running it, so it will not notice the fix: once a Valtimo with it is the floor, drop the
wrapping from the test's delegate and assert the diagnosis on the incident itself.
