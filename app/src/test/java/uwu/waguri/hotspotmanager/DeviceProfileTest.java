package uwu.waguri.hotspotmanager;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class DeviceProfileTest {
    private static final String FIRST = "02:ab:cd:33:44:55";
    private static final String SECOND = "04:de:fa:33:44:66";

    @Test
    public void macIdentityDoesNotDependOnTheCurrentIp() {
        DeviceProfile previous = new DeviceProfile(FIRST, DeviceProfile.Type.COMPUTER,
                "rndis0", "192.168.78.100", true);
        DeviceProfile reconnected = new DeviceProfile(FIRST.toUpperCase(), DeviceProfile.Type.COMPUTER,
                "rndis0", "192.168.78.150", true);
        assertEquals(previous.mac, reconnected.mac);
        assertEquals(previous.type, reconnected.type);
        assertNotEquals(previous.ip, reconnected.ip);
    }

    @Test
    public void macIdentityDoesNotDependOnInterface() {
        DeviceProfile wifi = new DeviceProfile(FIRST, DeviceProfile.Type.PHONE, "ap0", "192.168.77.10", true);
        DeviceProfile usb = new DeviceProfile(FIRST, DeviceProfile.Type.PHONE, "rndis0", "192.168.78.10", true);
        assertEquals(wifi.mac, usb.mac);
    }

    @Test
    public void randomizedUnicastMacsAreAcceptedWithoutClaimingADeviceType() {
        DeviceProfile profile = new DeviceProfile(FIRST, null, "ap0", "", false);
        assertEquals(DeviceProfile.Type.UNKNOWN, profile.type);
        assertFalse(profile.permanent);
    }

    @Test
    public void invalidAndGroupMacsAreRejected() {
        for (String value : new String[] {"", "invalid", "00:00:00:00:00:00",
                "ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01", "02:11:22:33:44:55; id", "$(id)"}) {
            assertThrows(IllegalArgumentException.class, () -> DeviceProfile.normalizeMac(value));
        }
    }

    @Test
    public void unsupportedInterfacesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceProfile(FIRST, null, "wlan0; id", "", true));
    }

    @Test
    public void blockAddsOnlyTheSelectedMacAndKeepsOtherEntries() {
        List<String> existing = new ArrayList<>(List.of(SECOND));
        assertEquals(Arrays.asList(SECOND, FIRST), DeviceProfile.updateBlocked(existing, FIRST, true));
        assertEquals(List.of(SECOND), existing);
    }

    @Test
    public void unblockAndResetRemoveOnlyTheSelectedMac() {
        assertEquals(List.of(SECOND), DeviceProfile.updateBlocked(
                Arrays.asList(FIRST, SECOND), FIRST, false));
    }

    @Test
    public void repeatedBlockDoesNotDuplicateTheSelectedMac() {
        assertEquals(Arrays.asList(SECOND, FIRST), DeviceProfile.updateBlocked(
                Arrays.asList(FIRST.toUpperCase(), SECOND, FIRST), FIRST, true));
    }

    @Test
    public void unknownMacRemovalKeepsTheEntireExistingList() {
        assertEquals(List.of(SECOND), DeviceProfile.updateBlocked(List.of(SECOND), FIRST, false));
    }

    @Test
    public void temporaryBlockIsRejectedBecauseNativeConfigurationPersists() {
        assertThrows(IllegalArgumentException.class,
                () -> DeviceProfile.requireRememberedBlock(true, false));
        DeviceProfile.requireRememberedBlock(true, true);
        DeviceProfile.requireRememberedBlock(false, false);
    }

    @Test
    public void storedUnknownTypeFallsBackWithoutGuessingFromTheMac() {
        assertEquals(DeviceProfile.Type.UNKNOWN, DeviceProfile.Type.fromStored("NewFutureType"));
        assertEquals(DeviceProfile.Type.UNKNOWN, DeviceProfile.Type.fromStored(null));
        assertEquals(DeviceProfile.Type.COMPUTER, DeviceProfile.Type.fromStored("COMPUTER"));
    }

    @Test
    public void profilesForDifferentMacsStayDistinctEvenWithTheSameIp() {
        DeviceProfile first = new DeviceProfile(FIRST, null, "ap0", "192.168.77.100", true);
        DeviceProfile second = new DeviceProfile(SECOND, null, "ap0", "192.168.77.100", true);
        assertNotEquals(first.mac, second.mac);
    }
}
