/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.nebula.gradle.plugin.imagebuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;
import com.redhat.et.libguestfs.LibGuestFSException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.apache.commons.io.IOUtils;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarInputStream;
import org.jgrapht.DirectedGraph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.experimental.dag.DirectedAcyclicGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author chris
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DockerOperations {

    private static final Logger LOG = LoggerFactory.getLogger(DockerOperations.class);

    public static enum DockerBackend {

        aufs, btrfs, devicemapper, vfs
    }

    public static class Image {

        String latest;
    }

    private void createLayersFiles(
            @Nonnull DirectedGraph<String, DefaultEdge> graph,
            @Nonnull File layersDirectory,
            @Nonnull String root) throws IOException {
        Set<String> vertexSet = graph.vertexSet();

        for (String vertex : vertexSet) {
            DijkstraShortestPath dsp = new DijkstraShortestPath(graph, vertex, root);
            GraphPath<String, DefaultEdge> path = dsp.getPath();
            File layer = new File(layersDirectory, vertex);

            Path p = layer.toPath().getParent();
            if (!p.toFile().exists()) {
                LOG.debug("Creating parent directory: " + p.toString());
                p.toFile().mkdirs();
            }

            LOG.debug("Creating new file: " + layer.getAbsolutePath());
            layer.createNewFile();

            try (FileOutputStream fos = new FileOutputStream(layer)) {
                for (DefaultEdge e : path.getEdgeList()) {
                    String target = graph.getEdgeTarget(e);
                    fos.write((target + "\n").getBytes());
                }
            }
        }
    }

    private void saveRepositoriesFile(
            @Nonnull Map<String, Object> repositoriesJson,
            @Nonnull Map<String, Object> existingRepositories,
            @Nonnull File dstFile) throws IOException {

        //TODO: merge together the repositories
        ObjectMapper mapper = new ObjectMapper();
        LOG.debug("Existing repositories: " + existingRepositories.toString());
        LOG.debug("New repositories: " + repositoriesJson.toString());

        //Overwrite that shit
        existingRepositories.putAll(repositoriesJson);

        LOG.debug("Combined repositories: " + existingRepositories.toString());

        mapper.writeValue(dstFile, existingRepositories);
    }

    private void createParentDirs(@Nonnull File f) {
        Path p = f.toPath().getParent();
        if (!p.toFile().exists())
            p.toFile().mkdirs();
    }

    private void unpackEntry(@Nonnull TarEntry t,
            @Nonnull File baseDirectory,
            @Nonnull TarInputStream inputStream) throws FileNotFoundException, IOException {
        LOG.debug("unpacking: " + t.getName());
        File destFile = new File(baseDirectory, t.getName());
        if (t.isDirectory()) {
            if (!destFile.exists()) {
                LOG.debug("Creating directory : " + destFile.getAbsolutePath());
                destFile.mkdirs();
            }
        } else {
            //Lets try to be somewhat efficient and write 128Kb writes
            createParentDirs(destFile);

            try (FileOutputStream fout = new FileOutputStream(destFile)) {
                IOUtils.copy(inputStream, fout);
            }

            try (TarInputStream layerInputStream = new TarInputStream(new FileInputStream(destFile))) {
                //Unpack this in the current directory

                TarEntry entry;
                while ((entry = layerInputStream.getNextEntry()) != null) {
                    if ("./".equals(entry.getName()))
                        continue;
                    File out = new File(baseDirectory, entry.getName());

                    if (entry.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }

                    //Ensure the parent directories exist for this file
                    createParentDirs(out);
                    try (FileOutputStream fout = new FileOutputStream(out)) {
                        IOUtils.copy(layerInputStream, fout);
                    }
                }
            }
        }
    }

    private void unpackAufsDockerImage(
            @Nonnull File src,
            @Nonnull File dstDir)
            throws LibGuestFSException,
            FileNotFoundException,
            IOException,
            DirectedAcyclicGraph.CycleFoundException {

        ObjectMapper mapper = new ObjectMapper();
        DirectedAcyclicGraph<String, DefaultEdge> root = new DirectedAcyclicGraph<>(DefaultEdge.class);

        Map<String, Object> tarRepositories = new HashMap<>();
        Map<String, Object> existingRepositories = new HashMap<>();

        String treeRoot = "";

        String existingRepoInfo = Files.toString(
                new File("/tmp/repositories-aufs"),
                Charset.defaultCharset());

        existingRepositories = mapper.readValue(
                existingRepoInfo,
                new TypeReference<Map<String, Object>>() {
                });

        try (TarInputStream inputStream = new TarInputStream(new FileInputStream(src))) {

            TarEntry entry;
            //Loop over the tar image
            while ((entry = inputStream.getNextEntry()) != null) {
                String name = entry.getName();

                if (name.endsWith("layer.tar")) {
                    /*
                     117ee323aaa9d1b136ea55e4421f4ce413dfc6c0cc6b2186dea6c88d93e1ad7c/VERSION
                     117ee323aaa9d1b136ea55e4421f4ce413dfc6c0cc6b2186dea6c88d93e1ad7c/json
                     117ee323aaa9d1b136ea55e4421f4ce413dfc6c0cc6b2186dea6c88d93e1ad7c/layer.tar
                     */
                    unpackEntry(entry, new File(dstDir, "diff"), inputStream);
                }

                if (name.endsWith("json")) {
                    //This is just so we can read the file and then throw it away.
                    try (ByteArrayOutputStream tempOutputStream = new ByteArrayOutputStream()) {
                        IOUtils.copy(inputStream, tempOutputStream);

                        DockerJson value = mapper.readValue(tempOutputStream.toString(), DockerJson.class);
                        if (value.parent == null) {
                            //This is the root
                            LOG.debug("Found root of tree: " + value.id);
                            treeRoot = value.id;

                            LOG.debug("Adding vertex: " + value.id);
                            root.addVertex(value.id);
                        } else {
                            root.addVertex(value.parent);
                            root.addVertex(value.id);
                            LOG.debug("Adding edge from " + value.id + " to " + value.parent);
                            root.addEdge(value.id, value.parent);
                        }
                    }
                }
                if ("repositories".equals(name)) {
                    //Save this file for later so we can add all the images to it
                    try (ByteArrayOutputStream tempOutputStream = new ByteArrayOutputStream()) {
                        IOUtils.copy(inputStream, tempOutputStream);

                        tarRepositories = mapper.readValue(tempOutputStream.toByteArray(),
                                new TypeReference<Map<String, Object>>() {
                                });
                        LOG.debug("Repositories: " + tarRepositories.toString());
                    }
                }
            }
        }
        if (treeRoot.isEmpty()) {
            throw new IllegalStateException("Root of docker container tree not found!");
        }

        //Save the RepositoriesJson file back out
        createLayersFiles(root, new File(dstDir, "layers"), treeRoot);
        saveRepositoriesFile(tarRepositories, existingRepositories, new File(dstDir, "repositories-new"));
    }

    public void unpackDockerImage(ImageTask.Context context,
            @Nonnull File src,
            @Nonnull File dstDir,
            DockerBackend backend) throws LibGuestFSException,
            IOException, FileNotFoundException,
            DirectedAcyclicGraph.CycleFoundException {
        Preconditions.checkState(
                src.exists(),
                "Source file does not exist: " + src.getAbsolutePath());
        Preconditions.checkState(
                dstDir.isDirectory(),
                "Destination is not a directory: " + dstDir.getAbsolutePath());
        switch (backend) {
            case aufs:
                unpackAufsDockerImage(src, dstDir);
            default:
                break;
        }
    }

    public static void main(String[] args) throws LibGuestFSException,
            IOException, FileNotFoundException, DirectedAcyclicGraph.CycleFoundException {
        DockerOperations cephOperations = new DockerOperations();
        cephOperations.unpackAufsDockerImage(new File("/home/chris/docker-image.tar"), new File("/tmp"));
    }
}
