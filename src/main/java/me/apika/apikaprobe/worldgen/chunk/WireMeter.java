package me.apika.apikaprobe.worldgen.chunk;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;

/**
 * How many bytes a real Java client would have received, for a bench
 * player whose connection has no socket (FakeConnection): the bandwidth
 * of Java players, against Bedrock players through Geyser, in
 * scripts/ci-geyser.sh. No Java client library supports this game
 * version yet, so the bench measures its own players' packets.
 *
 * Each packet is encoded with the game protocol's own codec (packet id
 * and body, as the game's encoder writes them), then framed as the
 * game's pipeline would: at or above the server's
 * network-compression-threshold compressed with zlib at the default
 * level, as CompressionEncoder does, below it sent with a zero length;
 * then prefixed with the frame length. A bundle counts its packets and
 * its two delimiters. Not counted: TCP/IP headers (a few percent on
 * full segments), and any other mod's compressor (Krypton uses its own
 * implementation of the same format).
 *
 * Encoding happens on the thread that sends; compression runs on one
 * daemon thread ("Ferrite wire meter"), so the server thread does not
 * pay for it.
 */
final class WireMeter {
	private static final ExecutorService COMPRESSOR = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Ferrite wire meter");
		t.setDaemon(true);
		return t;
	});
	private static final Deflater DEFLATER = new Deflater();
	private static final byte[] OUT = new byte[1 << 16];

	private final StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec;
	private final int threshold;
	final AtomicLong bytes = new AtomicLong();
	final AtomicLong packets = new AtomicLong();
	final AtomicLong encodeFailures = new AtomicLong();

	WireMeter(MinecraftServer server) {
		ProtocolInfo<ClientGamePacketListener> info =
				GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess()));
		this.codec = info.codec();
		this.threshold = server.getCompressionThreshold();
	}

	void reset() {
		flush();
		bytes.set(0);
		packets.set(0);
		encodeFailures.set(0);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	void count(Packet<?> packet) {
		if (packet instanceof BundlePacket<?> bundle) {
			for (Packet<?> part : bundle.subPackets()) count(part);
			// Two delimiters: frame length, uncompressed marker, packet id.
			bytes.addAndGet(threshold >= 0 ? 6 : 4);
			packets.addAndGet(2);
			return;
		}
		ByteBuf buf = Unpooled.buffer();
		byte[] data;
		try {
			codec.encode(buf, (Packet) packet);
			data = new byte[buf.readableBytes()];
			buf.readBytes(data);
		} catch (Throwable t) {
			encodeFailures.incrementAndGet();
			return;
		} finally {
			buf.release();
		}
		packets.incrementAndGet();
		if (threshold < 0) {
			bytes.addAndGet(varIntSize(data.length) + data.length);
		} else if (data.length < threshold) {
			int frame = 1 + data.length;
			bytes.addAndGet(varIntSize(frame) + frame);
		} else {
			COMPRESSOR.execute(() -> {
				int compressed = deflatedSize(data);
				int frame = varIntSize(data.length) + compressed;
				bytes.addAndGet(varIntSize(frame) + frame);
			});
		}
	}

	/** Waits for the compressions queued so far. */
	static void flush() {
		try {
			COMPRESSOR.submit(() -> { }).get(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			// Counted as far as it got.
		}
	}

	private static int deflatedSize(byte[] data) {
		DEFLATER.reset();
		DEFLATER.setInput(data);
		DEFLATER.finish();
		int size = 0;
		while (!DEFLATER.finished()) size += DEFLATER.deflate(OUT);
		return size;
	}

	private static int varIntSize(int value) {
		int size = 1;
		while ((value & ~0x7F) != 0) {
			size++;
			value >>>= 7;
		}
		return size;
	}
}
