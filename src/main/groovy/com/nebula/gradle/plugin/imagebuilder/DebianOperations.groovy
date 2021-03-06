package com.nebula.gradle.plugin.imagebuilder

import java.io.File;
import javax.annotation.Nonnull;
import com.redhat.et.libguestfs.GuestFS;
import com.redhat.et.libguestfs.Partition;
// import com.redhat.et.libguestfs.EventCallback;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;

// @Category(ImageTask)
class DebianOperations implements Operations {

	public static final String HOOK_INTERSTAGE = "interstage"

	static class Debootstrap {
		@Input
		String repository = "http://localhost:3142/ubuntu/"
		@Input
		String release = "quantal"
		@Input
		List<String> components = [ "main" ]
		@Input
		List<String> packages = []
		@Input
		List<String> debs = []

		Map<String, Closure> hooks = [:]

		@Input
		String variant = "-"
		@Input
		List<String> options = []

		@Input
		String installKernel = "/vmlinuz"
		// @Input
		// String installInitrd = "/initrd.img"
		@Input
		String installScript = """#!/bin/sh
/debootstrap/debootstrap --second-stage --keep-debootstrap-dir
/usr/bin/apt-get -y clean
sync
sleep 1
sync
sleep 1
sync
sleep 1
/bin/mount -o remount,ro /
poweroff -f
"""

		void repository(String repository) {
			this.repository = repository
		}

		void release(String release) {
			this.release = release
		}

		void components(String... components) {
			this.components.addAll(components)
		}

		void packages(String... packages) {
			this.packages.addAll(packages)
		}

		void debs(Object... debs) {
            for (Object deb : debs) {
                if (deb instanceof FileCollection) {
                    deb.each { file ->
                        this.debs.add(file.getAbsolutePath());
                    }
                }
                else {
                    this.debs.add(deb.toString());
                }
            }
		}

		void variant(String variant) {
			this.variant = variant
		}

		void options(String... options) {
			this.options.addAll(options)
		}
	}

	void debootstrap(@Nonnull Closure details) {
		Debootstrap d = new Debootstrap()
		d.with details

		customize {
			_debootstrap(delegate, d)
		}
	}

	private void hook(ImageTask.Context c, Debootstrap d, String name) {
		Closure hook = d.hooks.get(name)
		if (hook == null)
			return
		hook.call()
	}

	public void _debootstrap(ImageTask.Context c, Debootstrap d) {
		File tmpDir = getTmpDir();
		File fakerootStateFile = new File(tmpDir, name + '.state')
		def fakerootCommand = [ "fakeroot", "-s", fakerootStateFile ]
		File debootstrapDir = new File(tmpDir, name + '.root')
		debootstrapDir.mkdirs()
		String debootstrapComponents = d.components.join(",")
		String debootstrapPackages = d.packages.join(",")
		def debootstrapCommand = fakerootCommand + [
				"debootstrap",
					"--foreign", "--verbose",
					"--variant=" + d.variant,
					"--keyring", "/etc/apt/trusted.gpg",
					"--components=" + debootstrapComponents,
					"--include=" + debootstrapPackages
			] + d.options + [
					d.release, debootstrapDir, d.repository
			]

		logger.info("Executing " + debootstrapCommand.join(' '))
		project.exec {
			commandLine debootstrapCommand
		}

		File scriptFile = new File(debootstrapDir, 'debootstrap/install');
		scriptFile.text = d.installScript
		ant.chmod(file: scriptFile, perm: 'ugo+rx')

		File debDir = new File(debootstrapDir, 'var/cache/apt/archives/')
		File debNames = new File(debootstrapDir, 'debootstrap/base')
		File debPaths = new File(debootstrapDir, 'debootstrap/debpaths')
		d.debs.each { deb ->
			if (deb.startsWith("http")) {
				ant.get(
					src: deb,
					dest: debDir
				)
			} else {
				project.copy {
					// from project.file(deb)
					from project.file(deb)
					into debDir
				}
			}
			String debName = deb.substring(deb.lastIndexOf('/') + 1)
			String debFile = debName
			debName -= ~/_.*$/
			debNames << "$debName\n"
			debPaths << "$debName /var/cache/apt/archives/$debFile\n"
			// println "$debName -> $debFile"
		}

		hook(c, d, HOOK_INTERSTAGE)

		GuestFS g = c as GuestFS
		// g.mount(fsDevice(g, 1, 0), '/')
		fsUpload(g, debootstrapDir, fakerootStateFile)

		// Requesting the file closes the GuestFS handle.
		File imageFile = c as File
		def qemuCommand = [
			"qemu-system-x86_64",
				"-nodefaults", "-nodefconfig", "-no-user-config", "-no-reboot",
				// "-rtc", "driftfix=slew", "-no-hpet", "-no-kvm-pit-reinjection",
				// "-global", "virtio-blk-pci.scsi=off",
				"-machine", "accel=kvm:tcg",
				"-cpu", "host",
				"-m", "512",
				// We need the drive to appear as /dev/sda
				"-drive", "file=" + imageFile.absolutePath + ",cache=unsafe,aio=native",   // cache=none fails on tmpfs
				// "-device", "virtio-scsi-pci",
				// "-device", "scsi-hd,drive=disk0",
				"-kernel", d.installKernel,
				// "-initrd", d.installInitrd,	// Breaks if the initrd has custom scripts/local-top
				"-append", "rootwait root=/dev/sda rw console=tty0 console=ttyS0 init=/debootstrap/install",
				"-nographic", "-serial", "stdio", "-device", "sga"
		]
		logger.info("Executing " + qemuCommand)
		project.exec {
			commandLine qemuCommand
		}

		project.exec {
			commandLine "e2fsck", "-f", "-y", imageFile
			ignoreExitValue true
		}

		g = c as GuestFS
		g.aug_set('/files/etc/default/grub/GRUB_TERMINAL', 'console')
		g.aug_set('/files/etc/default/grub/GRUB_CMDLINE_LINUX_DEFAULT', 'nosplash')
		g.aug_set('/files/etc/default/locale/LANG', 'en_US.UTF-8')

		// project.delete(debootstrapDir, fakerootStateFile)
		File debootstrapLog = new File(tmpDir, name + '.debootstrap.log')
		g.download("/debootstrap/debootstrap.log",
				debootstrapLog.absolutePath)
		g.rm_rf("/debootstrap")
	}

	public void kernel_from(final String path) {
		download {
			from path
			into new File(owner.getOutputDir(), 'kernel')
		}
	}

	public void initrd_from(String path) {
		run '/usr/sbin/update-initramfs', '-u'
		download {
			from path
			into new File(owner.getOutputDir(), 'initrd')
		}
	}

}

