/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.rest.protocols.tcp;

import org.gridgain.client.marshaller.*;
import org.gridgain.client.marshaller.protobuf.*;
import org.gridgain.grid.kernal.processors.rest.client.message.*;
import org.gridgain.grid.util.nio.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.*;
import org.gridgain.testframework.junits.common.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

import static org.gridgain.grid.kernal.processors.rest.client.message.GridClientCacheRequest.GridCacheOperation.*;
import static org.gridgain.grid.kernal.processors.rest.protocols.tcp.GridMemcachedMessage.*;

/**
 * This class tests that parser confirms memcache extended specification.
 */
public class GridTcpRestParserSelfTest extends GridCommonAbstractTest {
    /** Protobuf marshaller. */
    private GridClientMarshaller marshaller = new GridClientProtobufMarshaller();

    /** Extras value. */
    public static final byte[] EXTRAS = new byte[]{
        (byte)0xDE, 0x00, (byte)0xBE, 0x00, //Flags, string encoding.
        0x00, 0x00, 0x00, 0x00 // Expiration value.
    };

    /**
     * @throws Exception If failed.
     */
    public void testSimplePacketParsing() throws Exception {
        GridNioSession ses = new GridMockNioSession();

        GridTcpRestParser parser = new GridTcpRestParser(log);

        byte hdr = MEMCACHE_REQ_FLAG;

        byte[] opCodes = {0x01, 0x02, 0x03};

        byte[] opaque = new byte[] {0x01, 0x02, 0x03, (byte)0xFF};

        String key = "key";

        String val = "value";

        for (byte opCode : opCodes) {
            ByteBuffer raw = rawPacket(hdr, opCode, opaque, key.getBytes(), val.getBytes(), EXTRAS);

            GridClientMessage msg = parser.decode(ses, raw);

            assertTrue(msg instanceof GridMemcachedMessage);

            GridMemcachedMessage packet = (GridMemcachedMessage)msg;

            assertEquals("Parser leaved unparsed bytes", 0, raw.remaining());

            assertEquals("Invalid opcode", opCode, packet.operationCode());
            assertEquals("Invalid key", key, packet.key());
            assertEquals("Invalid value", val, packet.value());
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testIncorrectPackets() throws Exception {
        final GridNioSession ses = new GridMockNioSession();

        final GridTcpRestParser parser = new GridTcpRestParser(log);

        final byte[] opaque = new byte[] {0x01, 0x02, 0x03, (byte)0xFF};

        final String key = "key";

        final String val = "value";

        GridTestUtils.assertThrows(log(), new Callable<Object>() {
            @Nullable @Override public Object call() throws Exception {
                parser.decode(ses, rawPacket((byte)0x01, (byte)0x01, opaque, key.getBytes(), val.getBytes(), EXTRAS));

                return null;
            }
        }, IOException.class, null);


        GridTestUtils.assertThrows(log(), new Callable<Object>() {
            @Nullable @Override public Object call() throws Exception {
                parser.decode(ses, rawPacket(MEMCACHE_REQ_FLAG, (byte)0x01, opaque, key.getBytes(), val.getBytes(), null));

                return null;
            }
        }, IOException.class, null);

        GridTestUtils.assertThrows(log(), new Callable<Object>() {
            @Nullable @Override public Object call() throws Exception {
                ByteBuffer fake = ByteBuffer.allocate(21);

                fake.put(GRIDGAIN_REQ_FLAG);
                fake.put(U.intToBytes(-5));
                fake.put(U.longToBytes(0));
                fake.put(U.longToBytes(0));

                fake.flip();

                parser.decode(ses, fake);

                return null;
            }
        }, IOException.class, null);
    }

    /**
     * @throws Exception If failed.
     */
    public void testCustomMessages() throws Exception {
        GridClientCacheRequest<String, Integer> req = new GridClientCacheRequest<>(CAS);

        req.key("key");
        req.value(1);
        req.value2(2);
        req.clientId(UUID.randomUUID());

        ByteBuffer raw = clientRequestPacket(req);

        GridNioSession ses = new GridMockNioSession();

        GridTcpRestParser parser = new GridTcpRestParser(log);

        GridClientMessage msg = parser.decode(ses, raw);

        assertNotNull(msg);

        assertEquals("Parser leaved unparsed bytes", 0, raw.remaining());

        assertTrue(msg instanceof GridClientCacheRequest);

        GridClientCacheRequest res = (GridClientCacheRequest) msg;

        assertEquals("Invalid operation", req.operation(), res.operation());
        assertEquals("Invalid clientId", req.clientId(), res.clientId());
        assertEquals("Invalid key", req.key(), res.key());
        assertEquals("Invalid value 1", req.value(), res.value());
        assertEquals("Invalid value 2", req.value2(), res.value2());
    }

    /**
     * @throws Exception If failed.
     */
    public void testMixedParsing() throws Exception {
        GridNioSession ses1 = new GridMockNioSession();

        GridNioSession ses2 = new GridMockNioSession();

        GridTcpRestParser parser = new GridTcpRestParser(log);

        GridClientCacheRequest<String, String> req = new GridClientCacheRequest<>(CAS);

        req.key("key");

        String val = "value";

        req.value(val);
        req.value2(val);
        req.clientId(UUID.randomUUID());

        byte[] opaque = new byte[]{0x01, 0x02, 0x03, (byte)0xFF};

        String key = "key";

        ByteBuffer raw1 = rawPacket(MEMCACHE_REQ_FLAG, (byte)0x01, opaque, key.getBytes(), val.getBytes(), EXTRAS);

        ByteBuffer raw2 = clientRequestPacket(req);

        raw1.mark();

        raw2.mark();

        int splits = Math.min(raw1.remaining(), raw2.remaining());

        for (int i = 1; i < splits; i++) {
            ByteBuffer[] packet1 = split(raw1, i);

            ByteBuffer[] packet2 = split(raw2, i);

            GridClientMessage msg = parser.decode(ses1, packet1[0]);

            assertNull(msg);

            msg = parser.decode(ses2, packet2[0]);

            assertNull(msg);

            msg = parser.decode(ses1, packet1[1]);

            assertTrue(msg instanceof GridMemcachedMessage);

            assertEquals(key, ((GridMemcachedMessage)msg).key());
            assertEquals(val, ((GridMemcachedMessage)msg).value());

            msg = parser.decode(ses2, packet2[1]);

            assertTrue(msg instanceof GridClientCacheRequest);

            assertEquals(val, ((GridClientCacheRequest)msg).value());
            assertEquals(val, ((GridClientCacheRequest)msg).value2());

            raw1.reset();

            raw2.reset();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testParseContinuousSplit() throws Exception {
        ByteBuffer tmp = ByteBuffer.allocate(10 * 1024);

        GridClientCacheRequest<String, Integer> req = new GridClientCacheRequest<>(CAS);

        req.key("key");
        req.value(1);
        req.value2(2);
        req.clientId(UUID.randomUUID());

        for (int i = 0; i < 5; i++)
            tmp.put(clientRequestPacket(req));

        tmp.flip();

        for (int splitPos = 0; splitPos < tmp.remaining(); splitPos++) {
            ByteBuffer[] split = split(tmp, splitPos);

            tmp.flip();

            GridNioSession ses = new GridMockNioSession();

            GridTcpRestParser parser = new GridTcpRestParser(log);

            Collection<GridClientCacheRequest> lst = new ArrayList<>(5);

            for (ByteBuffer buf : split) {
                GridClientCacheRequest r;

                while (buf.hasRemaining() && (r = (GridClientCacheRequest)parser.decode(ses, buf)) != null)
                    lst.add(r);

                assertTrue("Parser has left unparsed bytes.", buf.remaining() == 0);
            }

            assertEquals(5, lst.size());

            for (GridClientCacheRequest res : lst) {
                assertEquals("Invalid operation", req.operation(), res.operation());
                assertEquals("Invalid clientId", req.clientId(), res.clientId());
                assertEquals("Invalid key", req.key(), res.key());
                assertEquals("Invalid value 1", req.value(), res.value());
                assertEquals("Invalid value 2", req.value2(), res.value2());
            }
        }
    }

    /**
     * Tests correct parsing of client handshake packets.
     *
     * @throws Exception
     */
    public void testParseClientHandshake() throws Exception {
        for (int splitPos = 1; splitPos < 5; splitPos++) {
            log.info("Checking split position: " + splitPos);

            ByteBuffer tmp = clientHandshakePacket(U.OPTIMIZED_CLIENT_PROTO_ID);

            ByteBuffer[] split = split(tmp, splitPos);

            GridNioSession ses = new GridMockNioSession();

            GridTcpRestParser parser = new GridTcpRestParser(log);

            Collection<GridClientMessage> lst = new ArrayList<>(1);

            for (ByteBuffer buf : split) {
                GridClientMessage r;

                while (buf.hasRemaining() && (r = parser.decode(ses, buf)) != null)
                    lst.add(r);

                assertTrue("Parser has left unparsed bytes.", buf.remaining() == 0);
            }

            assertEquals(1, lst.size());

            GridClientHandshakeRequest req = (GridClientHandshakeRequest)F.first(lst);

            assertNotNull(req);
            assertEquals(U.OPTIMIZED_CLIENT_PROTO_ID, req.protocolId());
            assertTrue(Arrays.equals(new byte[]{5,0,0,0}, req.versionBytes()));
        }
    }

    /**
     * Splits given byte buffer into two byte buffers.
     *
     * @param original Original byte buffer.
     * @param pos Position at which buffer should be split.
     * @return Array of byte buffers.
     */
    private ByteBuffer[] split(ByteBuffer original, int pos) {

        byte[] data = new byte[pos];

        original.get(data);

        ByteBuffer[] res = new ByteBuffer[2];

        res[0] = ByteBuffer.wrap(data);

        data = new byte[original.remaining()];

        original.get(data);

        res[1] = ByteBuffer.wrap(data);

        return res;
    }

    /**
     * Assembles GridGain client packet.
     *
     * @param msg Message to serialize.
     * @return Raw message bytes.
     * @throws IOException If serialization failed.
     */
    private ByteBuffer clientRequestPacket(GridClientMessage msg) throws IOException {
        byte[] data = marshaller.marshal(msg);

        ByteBuffer res = ByteBuffer.allocate(data.length + 45);

        res.put(GRIDGAIN_REQ_FLAG);
        res.put(U.intToBytes(data.length + 40));
        res.put(U.longToBytes(msg.requestId()));
        res.put(U.uuidToBytes(msg.clientId()));
        res.put(U.uuidToBytes(msg.destinationId()));
        res.put(data);

        res.flip();

        return res;
    }

    /**
     * Assembles GridGain client handshake packet.
     *
     * @param protoId Protocol ID.
     * @return Raw message bytes.
     */
    private ByteBuffer clientHandshakePacket(byte protoId) {
        ByteBuffer res = ByteBuffer.allocate(6);

        res.put(new byte[] {
            GRIDGAIN_HANDSHAKE_FLAG, 5, 0, 0, 0, protoId
        });

        res.flip();

        return res;
    }

    /**
     * Assembles raw packet without any logical checks.
     *
     * @param magic Header for packet.
     * @param opCode Operation code.
     * @param opaque Opaque value.
     * @param key Key data.
     * @param val Value data.
     * @param extras Extras data.
     * @return Bute buffer containing assembled packet.
     */
    private ByteBuffer rawPacket(byte magic, byte opCode, byte[] opaque, @Nullable byte[] key, @Nullable byte[] val,
        @Nullable byte[] extras) {
        // 1k should be enough.
        ByteBuffer res = ByteBuffer.allocate(1024);

        res.put(magic);
        res.put(opCode);

        int keyLen = key == null ? 0 : key.length;
        int extrasLen = extras == null ? 0 : extras.length;
        int valLen = val == null ? 0 : val.length;

        res.putShort((short)keyLen);
        res.put((byte)extrasLen);

        // Data type is always 0.
        res.put((byte)0);

        // Reserved.
        res.putShort((short)0);

        // Total body.
        res.putInt(keyLen + extrasLen + valLen);

        // Opaque.
        res.put(opaque);

        // CAS
        res.putLong(0);

        if (extrasLen > 0)
            res.put(extras);

        if (keyLen > 0)
            res.put(key);

        if (valLen > 0)
            res.put(val);

        res.flip();

        return res;
    }
}
