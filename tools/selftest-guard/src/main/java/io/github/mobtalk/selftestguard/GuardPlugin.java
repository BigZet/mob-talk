package io.github.mobtalk.selftestguard;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the guard only when the JVM runs a self-test (a system property such as mobtalk.selftest,
 * divineblock.selftest, rusflora.hogweedtest, viewerparrots.qa.*), or when -Dselftest.guard=true forces it. */
public final class GuardPlugin implements IMixinConfigPlugin {
    private static final Pattern TEST = Pattern.compile("(?i).*(selftest|test|[.]qa[.]).*");
    static final boolean ACTIVE = active();

    private static boolean active() {
        String forced = System.getProperty("selftest.guard");
        if (forced != null) return Boolean.parseBoolean(forced);
        return System.getProperties().stringPropertyNames().stream()
                .filter(key -> !key.startsWith("java.") && !key.startsWith("sun.") && !key.startsWith("jdk.") && !key.startsWith("fabric."))
                .anyMatch(key -> TEST.matcher(key).matches());
    }

    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (ACTIVE) org.slf4j.LoggerFactory.getLogger("selftest-guard").info("[selftest-guard] {} -> {}", mixin, target);
        return ACTIVE;
    }
    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { }
}
