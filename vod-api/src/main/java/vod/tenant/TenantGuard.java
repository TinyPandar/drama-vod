package vod.tenant;

/** 租户只能来自认证上下文。资源不属于当前租户时当作不存在，避免泄露。 */
public final class TenantGuard {
    private TenantGuard() {}

    public static boolean visible(long actorTenantId, long resourceTenantId) {
        return actorTenantId == resourceTenantId;
    }

    public static void requireVisible(long actorTenantId, long resourceTenantId) {
        if (!visible(actorTenantId, resourceTenantId)) {
            throw new ResourceNotVisibleException();
        }
    }

    public static final class ResourceNotVisibleException extends RuntimeException {
        public ResourceNotVisibleException() {
            super("resource not visible");
        }
    }
}
