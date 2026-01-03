package io.opentelemetry.javaagent.bootstrap;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

// Too early for normal logging in OpenTelemetryAgent; keep this helper resilient.
@SuppressWarnings("SystemOut")
public final class ControlPlaneBridge {

  private static final String INSTRUMENTATION_HOLDER_CLASS =
      "io.opentelemetry.sdk.extension.controlplane.InstrumentationHolder";

  private static final AtomicBoolean NOT_FOUND_DIAGNOSTIC_EMITTED = new AtomicBoolean(false);

  public static void trySetInstrumentation(Instrumentation inst, ClassLoader agentClassLoader) {
    try {
      ClassLoader tccl = Thread.currentThread().getContextClassLoader();
      ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
      ClassLoader platformClassLoader = getPlatformClassLoaderOrNull();
      ClassLoader instrumentationClassLoader = inst != null ? inst.getClass().getClassLoader() : null;

      // Keep order stable & unique.
      Set<ClassLoader> candidates = new LinkedHashSet<>();
      addWithParents(candidates, tccl);
      addWithParents(candidates, agentClassLoader);
      addWithParents(candidates, instrumentationClassLoader);
      addWithParents(candidates, systemClassLoader);
      addWithParents(candidates, platformClassLoader);

      Class<?> holderClass = null;
      ClassLoader loadedFrom = null;
      for (ClassLoader loader : candidates) {
        if (loader == null) {
          continue;
        }
        try {
          holderClass = Class.forName(INSTRUMENTATION_HOLDER_CLASS, false, loader);
          loadedFrom = loader;
          break;
        } catch (ClassNotFoundException ignored) {
          // Try next loader.
        }
      }

      if (holderClass == null) {
        // Controlplane extension not on the classpath: nothing to do.
        emitNotFoundDiagnosticOnce(tccl, agentClassLoader, instrumentationClassLoader, systemClassLoader);
        return;
      }

      holderClass.getMethod("set", Instrumentation.class).invoke(null, inst);

      // Optional: quick confirmation for troubleshooting.
      System.err.println(
          "FINE "
              + ControlPlaneBridge.class.getName()
              + ": set controlplane InstrumentationHolder via loader="
              + classLoaderId(loadedFrom));
    } catch (Throwable t) {
      // Never fail agent startup due to optional integration.
      System.err.println(
          "FINE "
              + ControlPlaneBridge.class.getName()
              + ": failed to set controlplane InstrumentationHolder: "
              + t);
    }
  }

  private static void emitNotFoundDiagnosticOnce(
      ClassLoader tccl,
      ClassLoader agentClassLoader,
      ClassLoader instrumentationClassLoader,
      ClassLoader systemClassLoader) {
    if (!NOT_FOUND_DIAGNOSTIC_EMITTED.compareAndSet(false, true)) {
      return;
    }
    System.err.println(
        "FINE "
            + ControlPlaneBridge.class.getName()
            + ": controlplane InstrumentationHolder not found. "
            + "tccl="
            + classLoaderId(tccl)
            + ", agent="
            + classLoaderId(agentClassLoader)
            + ", inst="
            + classLoaderId(instrumentationClassLoader)
            + ", system="
            + classLoaderId(systemClassLoader));
  }

  private static void addWithParents(Set<ClassLoader> candidates, ClassLoader loader) {
    ClassLoader current = loader;
    while (current != null && candidates.add(current)) {
      current = current.getParent();
    }
  }

  private static ClassLoader getPlatformClassLoaderOrNull() {
    try {
      // Java 9+
      return (ClassLoader) ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
    } catch (Throwable ignored) {
      // Java 8 or restricted environment
      return null;
    }
  }

  private static String classLoaderId(ClassLoader loader) {
    if (loader == null) {
      return "<bootstrap>";
    }
    return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
  }

  private ControlPlaneBridge() {}
}
