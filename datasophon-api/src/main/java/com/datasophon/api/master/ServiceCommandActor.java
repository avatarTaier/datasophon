package com.datasophon.api.master;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.datasophon.api.service.ClusterInfoService;
import com.datasophon.api.service.ClusterServiceCommandHostService;
import com.datasophon.api.service.ClusterServiceCommandService;
import com.datasophon.api.utils.SpringTool;
import com.datasophon.api.service.ClusterServiceCommandHostCommandService;
import com.datasophon.common.Constants;
import com.datasophon.common.cache.CacheUtils;
import com.datasophon.common.command.GeneratePrometheusConfigCommand;
import com.datasophon.common.command.GenerateSRPromConfigCommand;
import com.datasophon.common.command.HdfsEcCommand;
import com.datasophon.common.model.UpdateCommandHostMessage;
import com.datasophon.dao.entity.ClusterInfoEntity;
import com.datasophon.dao.entity.ClusterServiceCommandEntity;
import com.datasophon.dao.entity.ClusterServiceCommandHostCommandEntity;
import com.datasophon.dao.entity.ClusterServiceCommandHostEntity;
import com.datasophon.dao.enums.ClusterState;
import com.datasophon.dao.enums.CommandState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

import java.util.Date;
import java.util.List;


public class ServiceCommandActor extends UntypedActor {

    private static final Logger logger = LoggerFactory.getLogger(ServiceCommandActor.class);

    @Override
    public void preRestart(Throwable reason, Option<Object> message) throws Exception {
        logger.info("service command actor restart because {}", reason.getMessage());
        super.preRestart(reason, message);
    }

    @Override
    public void onReceive(Object msg) throws Throwable {
        if (msg instanceof UpdateCommandHostMessage) {
            UpdateCommandHostMessage message = (UpdateCommandHostMessage) msg;
            ClusterInfoService clusterInfoService = SpringTool.getApplicationContext().getBean(ClusterInfoService.class);

            ClusterServiceCommandHostCommandService service = SpringTool.getApplicationContext().getBean(ClusterServiceCommandHostCommandService.class);
            ClusterServiceCommandHostService commandHostService = SpringTool.getApplicationContext().getBean(ClusterServiceCommandHostService.class);
            ClusterServiceCommandHostEntity commandHost = commandHostService.getOne(new QueryWrapper<ClusterServiceCommandHostEntity>().eq(Constants.COMMAND_HOST_ID, message.getCommandHostId()));
            Integer size = service.getHostCommandSizeByHostnameAndCommandHostId(message.getHostname(), message.getCommandHostId());
            Integer totalProgress = service.getHostCommandTotalProgressByHostnameAndCommandHostId(message.getHostname(), message.getCommandHostId());
            Integer progress = totalProgress / size;
            commandHost.setCommandProgress(progress);
            if (progress == 100) {
                List<ClusterServiceCommandHostCommandEntity> list =  service.findFailedHostCommand(message.getHostname(),message.getCommandHostId());
                if(list.size() >0){
                    commandHost.setCommandState(CommandState.FAILED);
                }else{
                    commandHost.setCommandState(CommandState.SUCCESS);
                }

            }
            commandHostService.update(commandHost, new QueryWrapper<ClusterServiceCommandHostEntity>().eq(Constants.COMMAND_HOST_ID, message.getCommandHostId()));
            //更新command
            ClusterServiceCommandService commandService = SpringTool.getApplicationContext().getBean(ClusterServiceCommandService.class);

            Integer size1 = commandHostService.getCommandHostSizeByCommandId(message.getCommandId());
            Integer totalProgress1 = commandHostService.getCommandHostTotalProgressByCommandId(message.getCommandId());
            Integer progress1 = totalProgress1 / size1;
            ClusterServiceCommandEntity command = commandService.getOne(new QueryWrapper<ClusterServiceCommandEntity>().eq(Constants.COMMAND_ID, message.getCommandId()));
            command.setCommandProgress(progress1);
            if (progress1 == 100) {
                command.setCommandState(CommandState.SUCCESS);
                command.setEndTime(new Date());
                //更新集群状态
                if (command.getCommandType() == 1) {
                    ClusterInfoEntity clusterInfo = clusterInfoService.getById(command.getClusterId());
                    if (clusterInfo.getClusterState().equals(ClusterState.NEED_CONFIG)) {
                        clusterInfo.setClusterState(ClusterState.RUNNING);
                        clusterInfoService.updateById(clusterInfo);
                    }
                    String serviceName = command.getServiceName();
//                    if("starrocks".equals(serviceName.toLowerCase())){
//                        logger.info("start to generate starrocks");
//                        ActorRef starRocksActor = (ActorRef) CacheUtils.get("starRocksActor");
//                        GenerateStarRocksHAMessage starRocksHAMessage = new GenerateStarRocksHAMessage();
//                        starRocksHAMessage.setServiceInstanceId(command.getServiceInstanceId());
//                        starRocksHAMessage.setClusterId(clusterInfo.getId());
//                        starRocksActor.tell(starRocksHAMessage,getSelf());
//                    }
                    if("hdfs".equals(serviceName.toLowerCase())){
                        ActorRef hdfsECActor = (ActorRef) CacheUtils.get("hdfsECActor");
                        HdfsEcCommand hdfsEcCommand = new HdfsEcCommand();
                        hdfsEcCommand.setServiceInstanceId(command.getServiceInstanceId());
                        hdfsECActor.tell(hdfsEcCommand,getSelf());
                    }
                    //更新prometheus配置
                    logger.info("start to generate prometheus config");
                    ActorRef prometheusActor = (ActorRef) CacheUtils.get("prometheusActor");
                    if("starrocks".equals(serviceName.toLowerCase())){
                        GenerateSRPromConfigCommand prometheusConfigCommand = new GenerateSRPromConfigCommand();
                        prometheusConfigCommand.setServiceInstanceId(command.getServiceInstanceId());
                        prometheusConfigCommand.setClusterFrame(clusterInfo.getClusterFrame());
                        prometheusConfigCommand.setClusterId(clusterInfo.getId());
                        prometheusActor.tell(prometheusConfigCommand, getSelf());
                    }else{
                        GeneratePrometheusConfigCommand prometheusConfigCommand = new GeneratePrometheusConfigCommand();
                        prometheusConfigCommand.setServiceInstanceId(command.getServiceInstanceId());
                        prometheusConfigCommand.setClusterFrame(clusterInfo.getClusterFrame());
                        prometheusConfigCommand.setClusterId(clusterInfo.getId());
                        prometheusActor.tell(prometheusConfigCommand, getSelf());
                    }
                    if("alertmanager".equals(serviceName.toLowerCase())){
                        //生成告警配置

                    }
                }
                List<ClusterServiceCommandHostEntity> list = commandHostService.findFailedCommandHost(message.getCommandId());
                if(list.size() > 0){
                    command.setCommandState(CommandState.FAILED);
                    command.setEndTime(new Date());
                }
            }
            commandService.update(command, new QueryWrapper<ClusterServiceCommandEntity>().eq(Constants.COMMAND_ID, command.getCommandId()));
        }
    }


}