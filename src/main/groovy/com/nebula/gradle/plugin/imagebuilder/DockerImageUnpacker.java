/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.nebula.gradle.plugin.imagebuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.redhat.et.libguestfs.LibGuestFSException;
import java.io.BufferedInputStream;
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
import org.apache.commons.io.FileUtils;
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
public class DockerImageUnpacker {

    private static final Logger LOG = LoggerFactory.getLogger(DockerImageUnpacker.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static enum DockerStorageBackend {

        aufs, btrfs, devicemapper, vfs
    }

    private void createLayersFiles(
            @Nonnull DirectedGraph<String, DefaultEdge> graph,
            @Nonnull File layersDirectory,
            @Nonnull String root) throws IOException {
        Set<String> vertexSet = graph.vertexSet();

        for (String vertex : vertexSet) {
            DijkstraShortestPath<String, DefaultEdge> dsp = new DijkstraShortestPath<>(graph, vertex, root);
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
        LOG.debug("Existing repositories: " + existingRepositories);
        LOG.debug("New repositories: " + repositoriesJson);

        //Overwrite that shit
        existingRepositories.putAll(repositoriesJson);

        LOG.debug("Combined repositories: " + existingRepositories.toString());

        mapper.writeValue(dstFile, existingRepositories);
    }

    private void unpackLayerEntry(
            @Nonnull TarInputStream imageTarStream,
            @Nonnull TarEntry imageTarEntry,
            @Nonnull File baseDirectory
    ) throws FileNotFoundException, IOException {
        File imageTarEntryFile = new File(baseDirectory, imageTarEntry.getName());
        LOG.debug("Unpacking " + imageTarEntry.getName() + " into " + imageTarEntryFile.getAbsolutePath());
        if (imageTarEntry.isDirectory()) {
            if (!imageTarEntryFile.isDirectory()) {
                FileUtils.forceMkdir(imageTarEntryFile);
            }
        } else {
            //Lets try to be somewhat efficient and write 128Kb writes
            Files.createParentDirs(imageTarEntryFile);
            Files.asByteSink(imageTarEntryFile).writeFrom(imageTarStream);

            try (TarInputStream layerTarStream = new TarInputStream(new FileInputStream(imageTarEntryFile))) {
                TarEntry layerTarEntry;
                while ((layerTarEntry = layerTarStream.getNextEntry()) != null) {
                    if ("./".equals(layerTarEntry.getName()))
                        continue;

                    File layerTarEntryFile = new File(baseDirectory, layerTarEntry.getName());

                    if (layerTarEntry.isDirectory()) {
                        FileUtils.forceMkdir(layerTarEntryFile);
                    } else {
                        //Ensure the parent directories exist for this file
                        Files.createParentDirs(layerTarEntryFile);
                        Files.asByteSink(layerTarEntryFile).writeFrom(layerTarStream);
                    }
                }
            }
        }
    }

    private void unpackAufsDockerImage(
            @Nonnull File srcFile,
            @Nonnull File dstDir,
            @Nonnull File existingRepositoryFile)
            throws LibGuestFSException,
            FileNotFoundException,
            IOException,
            DirectedAcyclicGraph.CycleFoundException {

        DirectedAcyclicGraph<String, DefaultEdge> root = new DirectedAcyclicGraph<>(DefaultEdge.class);

        Map<String, Object> tarRepositories = new HashMap<>();

        String treeRoot = null;

        String existingRepoInfo = Files.toString(
                existingRepositoryFile, 
                Charset.defaultCharset());

        Map<String, Object> existingRepositories = mapper.readValue(
                existingRepoInfo,
                new TypeReference<Map<String, Object>>() {
                });

        try (FileInputStream in = FileUtils.openInputStream(srcFile)) {
            TarInputStream imageTarStream = new TarInputStream(new BufferedInputStream(in));
            TarEntry imageTarEntry;
            while ((imageTarEntry = imageTarStream.getNextEntry()) != null) {
                /*
                 117ee323aaa9d1b136ea55e4421f4ce413dfc6c0cc6b2186dea6c88d93e1ad7c/VERSION
                 117ee323aaa9d1b136ea55e4421f4ce413dfc6c0cc6b2186dea6c88d93e1ad7c/json
                 117ee323aaa9d1b136ea55e4421f4ce413dfc6c0cc6b2186dea6c88d93e1ad7c/layer.tar
                 */
                String name = imageTarEntry.getName();

                if (name.endsWith("/layer.tar")) {
                    unpackLayerEntry(imageTarStream, imageTarEntry, new File(dstDir, "diff"));
                } else if (name.endsWith("/json")) {
                    //This is just so we can read the file and then throw it away.
                    byte[] data = ByteStreams.toByteArray(imageTarStream);
                    DockerTarballMetadata value = mapper.readValue(data, DockerTarballMetadata.class);
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
                } else if ("/repositories".equals(name)) {
                    //Save this file for later so we can add all the images to it
                    byte[] data = ByteStreams.toByteArray(imageTarStream);
                    tarRepositories = mapper.readValue(data, new TypeReference<Map<String, Object>>() {
                    });
                    LOG.debug("Repositories: " + tarRepositories.toString());
                }
            }
        }

        if (treeRoot == null)
            throw new IllegalStateException("Root of docker container tree not found!");

        //Save the RepositoriesJson file back out
        createLayersFiles(root, new File(dstDir, "layers"), treeRoot);
        saveRepositoriesFile(tarRepositories, existingRepositories, new File(dstDir, "repositories-new"));
    }

    public void unpackDockerImage(
            @Nonnull ImageTask.Context context,
            @Nonnull File srcFile,
            @Nonnull File dstDir,
            @Nonnull File existingRepositoryFile,
            @Nonnull DockerStorageBackend backend)
            throws LibGuestFSException,
            IOException,
            FileNotFoundException,
            DirectedAcyclicGraph.CycleFoundException {
        Preconditions.checkArgument(
                srcFile.isFile(),
                "Source is not a regular file: " + srcFile.getAbsolutePath());
        Preconditions.checkArgument(
                dstDir.isDirectory(),
                "Destination is not a directory: " + dstDir.getAbsolutePath());
        switch (backend) {
            case aufs:
                unpackAufsDockerImage(srcFile, dstDir, existingRepositoryFile);
                break;
            default:
                break;
        }
    }

    public static void main(String[] args) throws LibGuestFSException,
            IOException, FileNotFoundException, DirectedAcyclicGraph.CycleFoundException {
        DockerImageUnpacker cephOperations = new DockerImageUnpacker();
        File tmpDir = Files.createTempDir();
        cephOperations.unpackAufsDockerImage(
                new File("/home/chris/docker-image.tar"), 
                new File("/tmp"),
                new File("/tmp/repositories-aufs"));
    }
}
