package com.velocitypowered.proxy.protocol.netty;

import static com.velocitypowered.api.event.query.ProxyQueryEvent.QueryType.BASIC;
import static com.velocitypowered.api.event.query.ProxyQueryEvent.QueryType.FULL;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.velocitypowered.api.event.query.ProxyQueryEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.QueryResponse;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.protocol.ProtocolConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.kyori.text.serializer.ComponentSerializers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class GS4QueryHandler extends SimpleChannelInboundHandler<DatagramPacket> {

  private static final Logger logger = LogManager.getLogger(GS4QueryHandler.class);

  private static final short QUERY_MAGIC_FIRST = 0xFE;
  private static final short QUERY_MAGIC_SECOND = 0xFD;
  private static final byte QUERY_TYPE_HANDSHAKE = 0x09;
  private static final byte QUERY_TYPE_STAT = 0x00;
  private static final byte[] QUERY_RESPONSE_FULL_PADDING = new byte[]{0x73, 0x70, 0x6C, 0x69, 0x74,
      0x6E, 0x75, 0x6D, 0x00, (byte) 0x80, 0x00};
  private static final byte[] QUERY_RESPONSE_FULL_PADDING2 = new byte[]{0x01, 0x70, 0x6C, 0x61,
      0x79, 0x65, 0x72, 0x5F, 0x00, 0x00};

  // Contents to add into basic stat response. See ResponseWriter class below
  private static final Set<String> QUERY_BASIC_RESPONSE_CONTENTS = ImmutableSet.of(
      "hostname",
      "gametype",
      "map",
      "numplayers",
      "maxplayers",
      "hostport",
      "hostip"
  );

  private final Cache<InetAddress, Integer> sessions = CacheBuilder.newBuilder()
      .expireAfterWrite(30, TimeUnit.SECONDS)
      .build();

  @MonotonicNonNull
  private volatile List<QueryResponse.PluginInformation> pluginInformationList = null;

  private final VelocityServer server;

  public GS4QueryHandler(VelocityServer server) {
    this.server = server;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
    ByteBuf queryMessage = msg.content();
    InetAddress senderAddress = msg.sender().getAddress();

    // Allocate buffer for response
    ByteBuf queryResponse = ctx.alloc().buffer();
    DatagramPacket responsePacket = new DatagramPacket(queryResponse, msg.sender());

    try {
      // Verify query packet magic
      if (queryMessage.readUnsignedByte() != QUERY_MAGIC_FIRST
          || queryMessage.readUnsignedByte() != QUERY_MAGIC_SECOND) {
        throw new IllegalStateException("Invalid query packet magic");
      }

      // Read packet header
      short type = queryMessage.readUnsignedByte();
      int sessionId = queryMessage.readInt();

      switch (type) {
        case QUERY_TYPE_HANDSHAKE: {
          // Generate new challenge token and put it into the sessions cache
          int challengeToken = ThreadLocalRandom.current().nextInt();
          sessions.put(senderAddress, challengeToken);

          // Respond with challenge token
          queryResponse.writeByte(QUERY_TYPE_HANDSHAKE);
          queryResponse.writeInt(sessionId);
          writeString(queryResponse, Integer.toString(challengeToken));
          ctx.writeAndFlush(responsePacket);
          break;
        }

        case QUERY_TYPE_STAT: {
          // Check if query was done with session previously generated using a handshake packet
          int challengeToken = queryMessage.readInt();
          Integer session = sessions.getIfPresent(senderAddress);
          if (session == null || session != challengeToken) {
            throw new IllegalStateException("Invalid challenge token");
          }

          // Check which query response client expects
          if (queryMessage.readableBytes() != 0 && queryMessage.readableBytes() != 4) {
            throw new IllegalStateException("Invalid query packet");
          }

          // Build query response
          QueryResponse response = QueryResponse.builder()
              .hostname(ComponentSerializers.PLAIN
                  .serialize(server.getConfiguration().getMotdComponent()))
              .gameVersion(ProtocolConstants.SUPPORTED_GENERIC_VERSION_STRING)
              .map(server.getConfiguration().getQueryMap())
              .currentPlayers(server.getPlayerCount())
              .maxPlayers(server.getConfiguration().getShowMaxPlayers())
              .proxyPort(server.getConfiguration().getBind().getPort())
              .proxyHost(server.getConfiguration().getBind().getHostString())
              .players(server.getAllPlayers().stream().map(Player::getUsername)
                  .collect(Collectors.toList()))
              .proxyVersion("Velocity")
              .plugins(
                  server.getConfiguration().shouldQueryShowPlugins() ? getRealPluginInformation()
                      : Collections.emptyList())
              .build();

          boolean isBasic = queryMessage.readableBytes() == 0;

          // Call event and write response
          server.getEventManager()
              .fire(new ProxyQueryEvent(isBasic ? BASIC : FULL, senderAddress, response))
              .whenCompleteAsync((event, exc) -> {
                // Packet header
                queryResponse.writeByte(QUERY_TYPE_STAT);
                queryResponse.writeInt(sessionId);

                // Start writing the response
                ResponseWriter responseWriter = new ResponseWriter(queryResponse, isBasic);
                responseWriter.write("hostname", event.getResponse().getHostname());
                responseWriter.write("gametype", "SMP");

                responseWriter.write("game_id", "MINECRAFT");
                responseWriter.write("version", event.getResponse().getGameVersion());
                responseWriter.writePlugins(event.getResponse().getProxyVersion(),
                    event.getResponse().getPlugins());

                responseWriter.write("map", event.getResponse().getMap());
                responseWriter.write("numplayers", event.getResponse().getCurrentPlayers());
                responseWriter.write("maxplayers", event.getResponse().getMaxPlayers());
                responseWriter.write("hostport", event.getResponse().getProxyPort());
                responseWriter.write("hostip", event.getResponse().getProxyHost());

                if (!responseWriter.isBasic) {
                  responseWriter.writePlayers(event.getResponse().getPlayers());
                }

                // Send the response
                ctx.writeAndFlush(responsePacket);
              }, ctx.channel().eventLoop());

          break;
        }
        default:
          throw new IllegalStateException("Invalid query type: " + type);
      }
    } catch (Exception e) {
      logger.warn("Error while trying to handle a query packet from {}", msg.sender(), e);
      // NB: Only need to explicitly release upon exception, writing the response out will decrement the reference
      // count.
      responsePacket.release();
    }
  }

  private static void writeString(ByteBuf buf, String string) {
    buf.writeCharSequence(string, StandardCharsets.ISO_8859_1);
    buf.writeByte(0x00);
  }

  private List<QueryResponse.PluginInformation> getRealPluginInformation() {
    // Effective Java, Third Edition; Item 83: Use lazy initialization judiciously
    List<QueryResponse.PluginInformation> res = pluginInformationList;
    if (res == null) {
      synchronized (this) {
        if (pluginInformationList == null) {
          pluginInformationList = res = server.getPluginManager().getPlugins().stream()
              .map(PluginContainer::getDescription)
              .map(desc -> QueryResponse.PluginInformation
                  .of(desc.getName().orElse(desc.getId()), desc.getVersion().orElse(null)))
              .collect(Collectors.toList());
        }
      }
    }
    return res;
  }

  private static class ResponseWriter {

    private final ByteBuf buf;
    private final boolean isBasic;

    ResponseWriter(ByteBuf buf, boolean isBasic) {
      this.buf = buf;
      this.isBasic = isBasic;

      if (!isBasic) {
        buf.writeBytes(QUERY_RESPONSE_FULL_PADDING);
      }
    }

    // Writes k/v to stat packet body if this writer is initialized
    // for full stat response. Otherwise this follows
    // GS4QueryHandler#QUERY_BASIC_RESPONSE_CONTENTS to decide what
    // to write into packet body
    void write(String key, Object value) {
      if (isBasic) {
        // Basic contains only specific set of data
        if (!QUERY_BASIC_RESPONSE_CONTENTS.contains(key)) {
          return;
        }

        // Special case hostport
        if (key.equals("hostport")) {
          buf.writeShortLE((Integer) value);
        } else {
          writeString(buf, value.toString());
        }
      } else {
        writeString(buf, key);
        writeString(buf, value.toString());
      }
    }

    // Ends packet k/v body writing and writes stat player list to
    // the packet if this writer is initialized for full stat response
    void writePlayers(Collection<String> players) {
      if (isBasic) {
        return;
      }

      // Ends the full stat key-value body with \0
      buf.writeByte(0x00);

      buf.writeBytes(QUERY_RESPONSE_FULL_PADDING2);
      players.forEach(player -> writeString(buf, player));
      buf.writeByte(0x00);
    }

    void writePlugins(String serverVersion, Collection<QueryResponse.PluginInformation> plugins) {
      if (isBasic) {
        return;
      }

      StringBuilder pluginsString = new StringBuilder();
      pluginsString.append(serverVersion).append(':').append(' ');
      Iterator<QueryResponse.PluginInformation> iterator = plugins.iterator();
      while (iterator.hasNext()) {
        QueryResponse.PluginInformation info = iterator.next();
        pluginsString.append(info.getName());
        if (info.getVersion() != null) {
          pluginsString.append(' ').append(info.getVersion());
        }
        if (iterator.hasNext()) {
          pluginsString.append(';').append(' ');
        }
      }

      writeString(buf, pluginsString.toString());
    }
  }
}
