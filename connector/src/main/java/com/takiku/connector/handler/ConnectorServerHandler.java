package com.takiku.connector.handler;

import com.google.protobuf.Message;
import com.takiku.connector.domain.ClientConn;
import com.takiku.connector.domain.ClientConnContext;
import com.takiku.connector.service.ConnectorToClientService;
import com.takiku.connector.service.UserOnlineService;
import com.takiku.connector.service.rest.ConnectorRestService;
import com.takiku.connector.config.SpringUtil;
import domain.ack.ClientAckWindow;
import domain.ack.ServerAckWindow;
import exception.IMException;
import internal.InternalAck;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import parse.AbstractPackParser;
import po.BaseResponse;
import po.ShakeHands;
import po.UserCertification;
import protobuf.PackProtobuf;

import java.time.Duration;
import java.util.function.Consumer;

import static internal.Constants.SHAKE_HANDS_ACK_TYPE;
import static internal.Constants.SHAKE_HANDS_STATUS_SUCCESS;

/**
 *处理客户端的消息
 */
@ChannelHandler.Sharable
@Component
public class ConnectorServerHandler  extends SimpleChannelInboundHandler<Message> {

    private FromClientParser fromClientParser;

    private static ConnectorToClientService connectorToClientService;

    private static UserOnlineService userOnlineService;

    private static   ConnectorRestService connectorRestService;

    private ServerAckWindow serverAckWindow;
    private ClientAckWindow clientAckWindow;

    @Autowired
    private ClientConnContext clientConnContext;

    {
        connectorToClientService=SpringUtil.getBean(ConnectorToClientService.class);
        connectorRestService= SpringUtil.getBean(ConnectorRestService.class);
        userOnlineService=SpringUtil.getBean(UserOnlineService.class);
    }


    public ConnectorServerHandler(){
        fromClientParser=new FromClientParser();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        userOnlineService.userOffline(ctx);
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, Message message) throws Exception {
         PackProtobuf.Pack pack= (PackProtobuf.Pack) message;
         switch (pack.getPackType()){
             case HEART:
                 fromClientParser.parse(pack.getHeart(),channelHandlerContext);
             break;
             case MSG:
                 fromClientParser.parse(pack.getMsg(),channelHandlerContext);
                 break;
             case REPLY:
                 fromClientParser.parse(pack.getReply(),channelHandlerContext);
                 break;
             case SHAKEHANDS:
                 fromClientParser.parse(pack.getShakeHands(),channelHandlerContext);
                 break;
             case ACK:
                 fromClientParser.parse(pack.getAck(),channelHandlerContext);
                 break;
         }
    }

    class FromClientParser extends AbstractPackParser{

        @Override
        public void registerParsers() {

          register(PackProtobuf.ShakeHands.class,((m, channelHandlerContext) -> {
              if (auth(m)){
                  ClientConn conn= userOnlineService.userOnline(m.getUserId(),channelHandlerContext);
                  serverAckWindow = new ServerAckWindow(conn.getNetId(), 10, Duration.ofSeconds(5));
                  clientAckWindow = new ClientAckWindow(5);
                  channelHandlerContext.writeAndFlush(InternalAck.createAck(m.getMsgId(),SHAKE_HANDS_ACK_TYPE,SHAKE_HANDS_STATUS_SUCCESS));
              }
          }));

          register(PackProtobuf.Msg.class,((m, channelHandlerContext) -> offerChat(m.getHead().getMsgId(),m.getSerial(),
                  m,channelHandlerContext,ignore ->
                  connectorToClientService.doChatToClientOrTransferAndFlush(m))));
          register(PackProtobuf.Reply.class,((m, channelHandlerContext) -> offerChat(m.getMsgId(),m.getSerial(),
                  m,channelHandlerContext,ignore ->
                          connectorToClientService.doReplyToClientOrTransferAndFlush(m))));
        }
    }

    private void offerChat(String id,Long serial,Message m, ChannelHandlerContext ctx, Consumer<Message> consumer) {
        offer(id,serial ,m, ctx, consumer);
    }

    private void offer(String id,Long serial, Message copy, ChannelHandlerContext ctx, Consumer<Message> consumer) {
        if (clientAckWindow == null) {
            throw new IMException("client not greet yet");
        }
        clientAckWindow.offer(id,serial,
                ctx, copy, consumer);
    }
    private boolean auth(PackProtobuf.ShakeHands shakeHands){
      BaseResponse<UserCertification> userCertificationBaseResponse = connectorRestService.certification(new ShakeHands(shakeHands.getUserId(),shakeHands.getToken()));
      if (userCertificationBaseResponse.isSuccess()){
          //TODO 加密
          return true;
      }
           return false;
    }

}