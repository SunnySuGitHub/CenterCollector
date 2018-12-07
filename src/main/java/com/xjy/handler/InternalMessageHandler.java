package com.xjy.handler;

import com.xjy.entity.*;
import com.xjy.parms.*;
import com.xjy.processor.ExceptionProcessor;
import com.xjy.processor.InternalMsgProcessor;
import com.xjy.util.ConvertUtil;
import com.xjy.util.DBUtil;
import com.xjy.util.InternalProtocolSendHelper;
import com.xjy.util.LogUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import sun.rmi.runtime.Log;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @Author: Mr.Xu
 * @Date: Created in 15:23 2018/9/27
 * @Description: 内部协议业务消息处理器
 */
public class InternalMessageHandler extends ChannelHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            InternalMsgBody msgBody = (InternalMsgBody)msg;
            //LogUtil.DataMessageLog(msgBody.toString());
            String address = msgBody.getDeviceId();
            ConcurrentHashMap<String,Center> map = GlobalMap.getMap();
            if(!map.containsKey(address)){
                Center center = new Center(address,ctx);
                map.put(address,center);
                LogUtil.DataMessageLog(InternalMessageHandler.class,"收到集中器"+address+"的首条消息！\r\n"+msgBody.toString());
                //更新数据库中集中器状态
                DBUtil.updateCenterState(1,center);
            }else{
                map.get(address).setCtx(ctx);
            }
            Center currentCenter = map.get(address);

            if(currentCenter.getHeartBeatTime() == null ){
                currentCenter.setHeartBeatTime(LocalDateTime.now());
                DBUtil.updateheartBeatTime(currentCenter);
            }else{
                Duration duration = Duration.between(currentCenter.getHeartBeatTime(),LocalDateTime.now());
                if(duration.toMinutes() > 4){
                    currentCenter.setHeartBeatTime(LocalDateTime.now());
                    DBUtil.updateheartBeatTime(currentCenter);
                }
            }
            if(msgBody.getMsgType() != InternalMsgType.HEARTBEAT_PACKAGE){//如果不是心跳包，进一步处理

                String instruction = msgBody.getInstruction().trim();
                LogUtil.DataMessageLog(InternalMessageHandler.class,"收到指令类型:" + instruction);

                if(instruction.equals(InternalOrders.READ)){ //读取指令,按页解析读数
                    if(currentCenter.getDbId() == null) DBUtil.preprocessOfRead(currentCenter);//针对定时读取尚未初始化的情况
                    InternalMsgProcessor.readProcessor(currentCenter,msgBody);
                }
                else if(instruction.equals(InternalOrders.D_READ)){//开关阀后返回的表状态
                    InternalMsgProcessor.afterUpdateValveState(currentCenter,msgBody);
                }
                else if(instruction.equals(InternalOrders.COLLECT)){//采集指令，说明采集器已经开始采集
                    LogUtil.DataMessageLog(InternalMessageHandler.class,"集中器已经开始采集！");
                }
                else if(instruction.equals(InternalOrders.DOWNLOAD)){
                    //下载档案命令的处理器，先读取页数，是最后一页的话命令成功结束
                    InternalMsgProcessor.writeProcessor(currentCenter,msgBody);
                }
                else if(instruction.equals(InternalOrders.CLOCK)){//设备校时返回，设置命令成功，并设置定时采集
                    currentCenter.getCurCommand().setState(CommandState.SUCCESSED);
                    DBUtil.updateCommandState(CommandState.SUCCESSED,currentCenter);
                    InternalProtocolSendHelper.setTimingCollect(currentCenter);
                }
                else if(instruction.equals(InternalOrders.SCHEDUEL)){
                    LogUtil.DataMessageLog(InternalMessageHandler.class,"设置定时采集成功！");
                }
                else if(instruction.equals(InternalOrders.SUCCESE)) {//（采集）命令执行成功
                    LogUtil.DataMessageLog(InternalMessageHandler.class, "(采集)命令执行成功！");
                    //非定时采集
                    if(currentCenter.getCurCommand() != null || currentCenter.getCurCommand().getType()== CommandType.COLLECT_FOR_CENTER){//非定时采集
                        currentCenter.getCurCommand().setState(CommandState.SUCCESSED);
                        DBUtil.updateCommandState(CommandState.SUCCESSED, currentCenter);
                    }
                    InternalProtocolSendHelper.readNextPage(currentCenter,1);
                }
                else if(instruction.equals(InternalOrders.OPCHANNEL_FAILED)){//打开节点失败
                    LogUtil.DataMessageLog(InternalMessageHandler.class,"打开节点失败！");
                    //周全起见，关闭阀控节点
                    InternalProtocolSendHelper.closeChannel(currentCenter,currentCenter.getCurCommand());
                    currentCenter.getCurCommand().setState(CommandState.FAILED);
                    DBUtil.updateCommandState(CommandState.FAILED,currentCenter);
                }
                else if(instruction.equals(InternalOrders.OPEN_VALVE) || instruction.equals(InternalOrders.CLOSE_VALVE)){//开关阀返回
                    System.out.println("开关阀返回！");
                    if(currentCenter.getDbId() == null )DBUtil.preprocessOfRead(currentCenter);//初始化集中器在数据库中的id
                    InternalMsgProcessor.getValveInfo(currentCenter,msgBody);//获取开关阀信息
                    InternalProtocolSendHelper.closeChannel(currentCenter,currentCenter.getCurCommand());
                }
                else if(instruction.equals(InternalOrders.BEFORE_CLOSE)){//开关阀后读节点表返回
                    InternalMsgProcessor.getValveInfo(currentCenter,msgBody);//获取开关阀信息
                    InternalProtocolSendHelper.closeChannel(currentCenter,currentCenter.getCurCommand());
                }
                else if(instruction.equals(InternalOrders.CLOSE_CHANNEL)){//关通道，写入日志即可
                    LogUtil.DataMessageLog(InternalMessageHandler.class,"开关阀后关闭通道");
                }
            }
    }


    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        System.out.println("业务处理时异常");
        cause.printStackTrace();
        ExceptionProcessor.processAfterException(ctx);//将对应集中器的命令状态置为失败
        ctx.close();
    }
}
