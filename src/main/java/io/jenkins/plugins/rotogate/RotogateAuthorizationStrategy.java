package io.jenkins.plugins.rotogate;

import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.ProjectMatrixAuthorizationStrategy;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RotogateAuthorizationStrategy extends ProjectMatrixAuthorizationStrategy {

    private static final ConcurrentHashMap<String, ACL> aclCache = new ConcurrentHashMap<>();

    public static void invalidateCache() {
        aclCache.clear();
    }

    /**
     * True when the permission id names an admin-equivalent ("dangerous") permission that must never
     * be granted through Rotogate - {@code RunScripts}, {@code ConfigureUpdateCenter} and
     * {@code UploadPlugins}. Each is effectively full control of Jenkins (arbitrary code execution),
     * which is why core/matrix-auth hide them from the normal permission matrix. We source the set
     * from matrix-auth's own {@link GlobalMatrixAuthorizationStrategy#DANGEROUS_PERMISSIONS} so any
     * future additions there are honoured automatically, rather than hard-coding a blocklist.
     */
    public static boolean isDangerousPermission(String permissionId) {
        if (permissionId == null) {
            return false;
        }
        Permission p = Permission.fromId(permissionId);
        return p != null && GlobalMatrixAuthorizationStrategy.DANGEROUS_PERMISSIONS.contains(p);
    }

    @Override
    public ACL getACL(AbstractItem item) {
        RotogateAssignmentConfig config = RotogateAssignmentConfig.get();
        if (config == null || config.getAssignments().isEmpty()) {
            return super.getACL(item);
        }
        return aclCache.computeIfAbsent(item.getFullName(), RotogateItemACL::new);
    }

    @DataBoundConstructor
    public RotogateAuthorizationStrategy() {
        super();
    }

    /**
     * Evicts cached per-item ACLs when items are renamed, moved or deleted - otherwise the
     * cache (keyed by full name) accumulates stale entries for the JVM's lifetime. Clearing
     * the whole cache is cheap: entries are rebuilt lazily on the next permission check.
     */
    @Extension
    public static final class CacheEvictingItemListener extends hudson.model.listeners.ItemListener {
        @Override public void onDeleted(hudson.model.Item item) { invalidateCache(); }
        @Override public void onRenamed(hudson.model.Item item, String oldName, String newName) { invalidateCache(); }
        @Override public void onLocationChanged(hudson.model.Item item, String oldFullName, String newFullName) { invalidateCache(); }
    }

    @Extension
    public static final class ConverterImpl extends GlobalMatrixAuthorizationStrategy.ConverterImpl {
        @Override
        public boolean canConvert(Class type) {
            return type == RotogateAuthorizationStrategy.class;
        }

        @Override
        public GlobalMatrixAuthorizationStrategy create() {
            return new RotogateAuthorizationStrategy();
        }
    }

    @Extension
    @Symbol("rotogateAuthorization")
    public static final class DescriptorImpl extends ProjectMatrixAuthorizationStrategy.DescriptorImpl {

        @Override
        public String getDisplayName() {
            return "Rotogate Authorization Strategy";
        }

        @Override
        protected GlobalMatrixAuthorizationStrategy create() {
            return new RotogateAuthorizationStrategy();
        }

        /**
         * Returns true when the existing strategy has permissions to migrate.
         */
        public boolean shouldShowMigrationNotice() {
            GlobalMatrixAuthorizationStrategy source = getMigrationSource();
            return source != null && !source.getGrantedPermissionEntries().isEmpty();
        }

        /**
         * Returns the current GlobalMatrix or ProjectMatrix strategy when switching to Rotogate
         * for the first time. Returns null if Rotogate is already active.
         */
        public GlobalMatrixAuthorizationStrategy getMigrationSource() {
            AuthorizationStrategy current = Jenkins.get().getAuthorizationStrategy();
            if (current instanceof GlobalMatrixAuthorizationStrategy
                    && !(current instanceof RotogateAuthorizationStrategy)) {
                return (GlobalMatrixAuthorizationStrategy) current;
            }
            return null;
        }

        /**
         * Returns an RotogateAuthorizationStrategy pre-populated with permissions from the
         * existing matrix strategy, for display in the migration UI.
         */
        public RotogateAuthorizationStrategy getPrePopulatedInstance() {
            GlobalMatrixAuthorizationStrategy source = getMigrationSource();
            if (source == null) return null;
            RotogateAuthorizationStrategy pre = new RotogateAuthorizationStrategy();
            for (Map.Entry<Permission, Set<PermissionEntry>> e : source.getGrantedPermissionEntries().entrySet()) {
                for (PermissionEntry pe : e.getValue()) {
                    pre.add(e.getKey(), pe);
                }
            }
            return pre;
        }
    }
}
