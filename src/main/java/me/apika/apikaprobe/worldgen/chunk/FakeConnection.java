package me.apika.apikaprobe.worldgen.chunk;

import java.lang.reflect.Field;
import java.util.function.Consumer;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * The network connection of a bench player (FakeExplorers): no socket.
 * The server builds every packet as for a real client, chunk packets
 * included, and hands it to send(), which passes it to the explorer and
 * drops it. Protocol switches and disconnects do nothing. An embedded
 * channel stands in for the socket, for code that reads the channel.
 * With a WireMeter attached, each packet is also measured as a real
 * connection would have sent it.
 */
final class FakeConnection extends Connection {
	private final Consumer<Packet<?>> sink;
	private volatile boolean open = true;
	/** Measures what a real client would have received; null when off. */
	volatile WireMeter meter;

	FakeConnection(Consumer<Packet<?>> sink) {
		super(PacketFlow.SERVERBOUND);
		this.sink = sink;
		try {
			Field channel = Connection.class.getDeclaredField("channel");
			channel.setAccessible(true);
			channel.set(this, new EmbeddedChannel());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("no channel field on Connection", e);
		}
	}

	@Override
	public void send(Packet<?> packet) {
		deliver(packet);
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener) {
		deliver(packet);
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
		deliver(packet);
	}

	private void deliver(Packet<?> packet) {
		if (!open) return;
		WireMeter m = meter;
		if (m != null) m.count(packet);
		sink.accept(packet);
	}

	@Override
	public void flushChannel() {
	}

	@Override
	public boolean isConnected() {
		return open;
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
	}

	@Override
	public void setupOutboundProtocol(ProtocolInfo<?> protocol) {
	}

	@Override
	public void setListenerForServerboundHandshake(PacketListener listener) {
	}

	@Override
	public void setReadOnly() {
	}

	@Override
	public void handleDisconnection() {
	}

	@Override
	public void disconnect(Component reason) {
		open = false;
	}

	@Override
	public void disconnect(DisconnectionDetails details) {
		open = false;
	}

	void close() {
		open = false;
	}
}
