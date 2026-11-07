package uwu.waguri.hotspotmanager;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public final class TrafficNetlinkTest {
    private static final String COOKIE = "0123456789abcdef0123456789abcdef";
    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder()); }
    private static byte[] cat(byte[]... values) {
        int size = 0; for (byte[] value : values) size += value.length;
        ByteBuffer b = buffer(size); for (byte[] value : values) b.put(value); return b.array();
    }
    private static byte[] attr(int type, byte[] data) {
        return buffer((data.length + 7) & ~3).putShort((short) (data.length + 4)).putShort((short) type).put(data).array();
    }
    private static byte[] integer(int n) { return buffer(4).putInt(n).array(); }
    private static byte[] action(long bytes, byte[] cookie) {
        byte[] tbf = buffer(56).putInt(17).putInt(3).array();
        return cat(attr(1, new byte[] {'p','o','l','i','c','e',0}), attr(2, attr(1, tbf)),
                attr(4, cat(attr(6, new byte[0]), attr(1, buffer(16).putLong(bytes).putInt(123).array()),
                        attr(6, new byte[0]))), attr(6, cookie));
    }
    private static byte[] message(long bytes, int parent, int sequence) {
        byte[] cookie = new byte[16];
        for (int i=0;i<16;i++) cookie[i]=(byte) Integer.parseInt(COOKIE.substring(i*2,i*2+2),16);
        byte[] options = cat(attr(5,new byte[16]), attr(7, cat(attr(1,action(bytes + 8000,new byte[0])),attr(2,action(bytes,cookie)))));
        byte[] body = cat(buffer(20).putInt(0).putInt(42).putInt(0x80000800).putInt(parent).putInt((50000 << 16) | 8).array(),
                attr(1,new byte[] {'u','3','2',0}), attr(2,options));
        return cat(buffer(16).putInt(16+body.length).putShort((short)44).putShort((short)2).putInt(sequence).putInt(3000).array(), body);
    }
    private static byte[] done() { return buffer(20).putInt(20).putShort((short)3).putShort((short)2).putInt(1).putInt(0).putInt(0).array(); }

    @Test public void wireRequestHasCorrectFilterMessageAndClsactParent() {
        ByteBuffer b = ByteBuffer.wrap(TrafficNetlink.request(42,0xfffffff2,7)).order(ByteOrder.nativeOrder());
        assertEquals(36,b.getInt()); assertEquals(46,b.getShort()); assertEquals(0x301,b.getShort());
        assertEquals(7,b.getInt()); assertEquals(42,b.getInt(20)); assertEquals(0xfffffff2,b.getInt(28));
    }
    @Test public void kernelBytesAndFullCookieDoNotDependOnTcDisplayFormatting() {
        byte[] packet = cat(message(5000000000L,0xfffffff2,1),done());
        StringBuilder out = new StringBuilder();
        assertTrue(TrafficNetlink.append(packet,packet.length,1,42,0xfffffff2,out));
        assertEquals(5000000000L,TrafficRules.acceptedBytes(out.toString()));
        assertTrue(TrafficRules.ownsPreference(out.toString(),COOKIE));
        assertTrue(out.toString().contains("fh 800::800"));
    }
    @Test public void otherInterfaceOrDirectionCannotSupplyAClientsCounter() {
        byte[] packet = cat(message(500,0xfffffff2,1),done());
        StringBuilder out = new StringBuilder();
        assertTrue(TrafficNetlink.append(packet,packet.length,1,43,0xfffffff2,out)); assertEquals("",out.toString());
        assertTrue(TrafficNetlink.append(packet,packet.length,1,42,0xfffffff3,out)); assertEquals("",out.toString());
    }
    @Test public void counterChangesKeepTheFingerprintStable() {
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        byte[] first = message(500,0xfffffff2,1), second = message(1000,0xfffffff2,1);
        TrafficNetlink.append(first,first.length,1,42,0xfffffff2,a);
        TrafficNetlink.append(second,second.length,1,42,0xfffffff2,b);
        assertEquals(TrafficRules.fingerprint(a.toString()),TrafficRules.fingerprint(b.toString()));
    }
    @Test public void partialRepliesDoNotPretendTheDumpIsComplete() {
        byte[] packet = message(50,0xfffffff2,1);
        assertFalse(TrafficNetlink.append(packet,packet.length,1,42,0xfffffff2,new StringBuilder()));
    }
    @Test public void corruptPacketsAndWrongSequencesAreRejected() {
        byte[] packet = message(50,0xfffffff2,1);
        assertThrows(IllegalStateException.class,()->TrafficNetlink.append(packet,packet.length-4,1,42,0xfffffff2,new StringBuilder()));
        assertThrows(IllegalStateException.class,()->TrafficNetlink.append(packet,packet.length,2,42,0xfffffff2,new StringBuilder()));
        byte[] invalid = packet.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.nativeOrder()).putInt(8);
        assertThrows(IllegalStateException.class,()->TrafficNetlink.append(invalid,invalid.length,1,42,0xfffffff2,new StringBuilder()));
    }
    @Test public void kernelErrorsAndInterruptedDumpsAreNotZeroSpeeds() {
        byte[] error = buffer(20).putInt(20).putShort((short)2).putShort((short)0).putInt(1).putInt(0).putInt(-1).array();
        assertThrows(IllegalStateException.class,()->TrafficNetlink.append(error,error.length,1,42,0xfffffff2,new StringBuilder()));
        byte[] interrupted = done(); ByteBuffer.wrap(interrupted).order(ByteOrder.nativeOrder()).putShort(6,(short)0x12);
        assertThrows(IllegalStateException.class,()->TrafficNetlink.append(interrupted,interrupted.length,1,42,0xfffffff2,new StringBuilder()));
    }
}
