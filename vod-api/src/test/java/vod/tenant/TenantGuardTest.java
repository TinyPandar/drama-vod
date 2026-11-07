package vod.tenant;

import org.junit.jupiter.api.Test;
import vod.job.JobGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantGuardTest {
    @Test
    void otherTenantIsNotVisible() {
        assertTrue(TenantGuard.visible(1, 1));
        assertFalse(TenantGuard.visible(1, 2));
        assertThrows(TenantGuard.ResourceNotVisibleException.class, () -> TenantGuard.requireVisible(1, 2));
    }

    @Test
    void repeatedTranscodeRequestKeepsOneJob() {
        JobGate gate = new JobGate();
        long first = gate.getOrCreate(9, "ladder_v1");
        long again = gate.getOrCreate(9, "ladder_v1");
        long otherProfile = gate.getOrCreate(9, "preview");
        assertEquals(first, again);
        assertFalse(first == otherProfile);
    }
}
