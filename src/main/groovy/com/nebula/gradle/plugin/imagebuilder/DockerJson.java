/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.nebula.gradle.plugin.imagebuilder;

import java.util.List;

/**
 *
 * @author chris
 */
public class DockerJson {
    public static class ContainerConfig{
        public String Hostname;
        public String Domainname;
        public String User;
        public long Memory;
        public long MemorySwap;
        public int CpuShares;
        public String Cpuset;
        public boolean AttachStdin;
        public boolean AttachStdout;
        public boolean AttachStderr;
        public String PortSpecs;
        public String ExposedPorts;
        public boolean Tty;
        public boolean OpenStdin;
        public boolean StdinOnce;
        public List<String> Env;
        public List<String> Cmd;
        public String Dns;
        public String Image;
        public String Volumes;
        public String VolumesFrom;
        public String WorkingDir;
        public String Entrypoint;
        public boolean NetworkDisabled;
        public String MacAddress;
        public List<?> OnBuild;

        @Override
        public String toString() {
            return "ContainerConfig{" + "Hostname=" + Hostname + ", Domainname=" + Domainname + ", User=" + User + ", Memory=" + Memory + ", MemorySwap=" + MemorySwap + ", CpuShares=" + CpuShares + ", Cpuset=" + Cpuset + ", AttachStdin=" + AttachStdin + ", AttachStdout=" + AttachStdout + ", AttachStderr=" + AttachStderr + ", PortSpecs=" + PortSpecs + ", ExposedPorts=" + ExposedPorts + ", Tty=" + Tty + ", OpenStdin=" + OpenStdin + ", StdinOnce=" + StdinOnce + ", Env=" + Env + ", Cmd=" + Cmd + ", Image=" + Image + ", Volumes=" + Volumes + ", WorkingDir=" + WorkingDir + ", Entrypoint=" + Entrypoint + ", NetworkDisabled=" + NetworkDisabled + ", MacAddress=" + MacAddress + ", OnBuild=" + OnBuild + '}';
        }

    }
    public String id;
    public String comment;
    public String parent;
    public String created;
    public String container;
    public ContainerConfig container_config;
    public ContainerConfig config;
    public String docker_version;
    public String architecture;
    public String os;
    public String checksum;
    public long Size;

    @Override
    public String toString() {
        return "DockerJson{" + "id=" + id + ", parent=" + parent + ", created=" + created + ", container=" + container + ", container_config=" + container_config + ", config=" + config + ", docker_version=" + docker_version + ", architecture=" + architecture + ", os=" + os + ", checksum=" + checksum + ", Size=" + Size + '}';
    }

}
