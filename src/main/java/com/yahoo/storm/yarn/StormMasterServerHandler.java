/*
 * Copyright (c) 2013 Yahoo! Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.storm.yarn;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.apache.thrift7.TException;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import backtype.storm.Config;

import com.google.common.base.Joiner;
import com.yahoo.storm.yarn.generated.StormMaster;

public class StormMasterServerHandler implements StormMaster.Iface {
    private static final Logger LOG = LoggerFactory.getLogger(StormMasterServerHandler.class);
    @SuppressWarnings("rawtypes")
    Map _storm_conf;
    StormAMRMClient _client;
    MasterServer _masterServer;
    
    StormMasterServerHandler(@SuppressWarnings("rawtypes") Map storm_conf, StormAMRMClient client) {
        _storm_conf = storm_conf;
        try {
            String host_addr = InetAddress.getLocalHost().getHostAddress();
            LOG.info("Storm master host:"+host_addr);
            _storm_conf.put(Config.NIMBUS_HOST, host_addr);
        } catch (UnknownHostException ex) {
        }
        Util.rmNulls(_storm_conf);
        _client = client;
    }

    void init(MasterServer masterServer) {
        _masterServer = masterServer;
    }

    void stop() {
        try {
            stopSupervisors();
            stopUI();
            stopNimbus();
        } catch (TException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Override
    public String getStormConf() throws TException {
        LOG.info("getting configuration...");
        return JSONValue.toJSONString(_storm_conf);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setStormConf(String storm_conf) throws TException {
        LOG.info("setting configuration...");

        // stop processes
        stopSupervisors();
        stopUI();
        stopNimbus();

        Object json = JSONValue.parse(storm_conf);
        if (!json.getClass().isAssignableFrom(Map.class)) {
            LOG.warn("Could not parse configuration into a Map: " + storm_conf);
            return;
        }
        Map<?, ?> new_conf = (Map<?, ?>)json;
        _storm_conf.putAll(new_conf);
        Util.rmNulls(_storm_conf);

        // start processes
        startNimbus();
        startUI();
        startSupervisors();
    }

    @Override
    public void addSupervisors(int number) throws TException {
        LOG.info("adding "+number+" supervisors...");
        _client.addSupervisors(number);
    }

    class StormProcess extends Thread {
        Process _process;
        String _name;

        public StormProcess(String name){
            _name = name;
        }

        public void run(){
            startStormProcess();
            try {
                _process.waitFor();
                LOG.info("Storm process "+_name+" stopped");
            } catch (InterruptedException e) {
                LOG.info("Interrupted => will stop the storm process too");
                _process.destroy();
            }
        }

        private void writeConfigFile() throws IOException {
            File configFile = new File("am-config/storm.yaml").getAbsoluteFile();
            configFile.getParentFile().mkdirs();
            Yaml yaml = new Yaml();
            yaml.dump(_storm_conf, new FileWriter(configFile));
        }

        private void startStormProcess() {
            try {
                writeConfigFile();
                LOG.info("Running: " + Joiner.on(" ").join(buildCommands()));
                ProcessBuilder builder =
                        new ProcessBuilder(buildCommands());
                builder.redirectError(Redirect.INHERIT);
                builder.redirectOutput(Redirect.INHERIT);

                _process = builder.start();
            } catch (IOException e) {
                LOG.warn("Error starting nimbus process ", e);
            }
        }

        private List<String> buildCommands() throws IOException {
            if (_name == "nimbus") {
                return Util.buildNimbusCommands(_storm_conf);
            } else if (_name == "ui") {
                return Util.buildUICommands(_storm_conf);
            }

            throw new IllegalArgumentException(
                    "Cannot build command list for \"" + _name + "\"");
        }

        public void stopStormProcess() {
            _process.destroy();
        }
    }

    StormProcess nimbusProcess;
    StormProcess uiProcess;

    @Override
    public void startNimbus() {
        LOG.info("starting nimbus...");
        synchronized(this) {
            if (nimbusProcess!=null && nimbusProcess.isAlive()){
                LOG.info("Received a request to start nimbus, but it is running now");
                return;
            }
            nimbusProcess = new StormProcess("nimbus");
            nimbusProcess.start(); 
        }       
    }

    @Override
    public void stopNimbus() {
        synchronized(this) {
            if (nimbusProcess == null) return;
            LOG.info("stopping nimbus...");
            if (!nimbusProcess.isAlive()){
                LOG.info("Received a request to stop nimbus, but it is not running now");
                return;
            }
            nimbusProcess.stopStormProcess();
            nimbusProcess = null;
        }
    }

    @Override
    public void startUI() throws TException {
        LOG.info("starting UI...");
        synchronized(this) {
            if (uiProcess!=null && uiProcess.isAlive()){
                LOG.info("Received a request to start UI, but it is running now");
                return;
            }
            uiProcess = new StormProcess("ui");
            uiProcess.start();
        } 
    }

    @Override
    public void stopUI() throws TException {
        synchronized(this) {
            if (uiProcess == null) return;
            LOG.info("stopping UI...");
            if (!uiProcess.isAlive()){
                LOG.info("Received a request to stop UI, but it is not running now");
                return;
            }
            uiProcess.stopStormProcess();
            uiProcess = null;
        }
    }

    @Override
    public void startSupervisors() throws TException {
        LOG.info("starting supervisors...");
        _client.startAllSupervisors();
    }

    @Override
    public void stopSupervisors() throws TException {
        LOG.info("stopping supervisors...");
        _client.stopAllSupervisors();
    }

    @Override
    public void shutdown() throws TException {
        LOG.info("shutdown storm master...");
        _masterServer.stop();
    }
}
