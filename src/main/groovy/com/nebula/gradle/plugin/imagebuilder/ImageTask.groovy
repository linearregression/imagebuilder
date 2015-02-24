package com.nebula.gradle.plugin.imagebuilder

import javax.annotation.Nonnull
import com.google.common.base.Charsets
import com.redhat.et.libguestfs.GuestFS
import com.redhat.et.libguestfs.Partition
import com.redhat.et.libguestfs.LibGuestFSException
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile

class ImageTask extends AbstractImageTask {

	// @Input
	List<Closure> customizations = new ArrayList<>()

	public ImageTask() {
		outputs.upToDateWhen { false }
		description = "Builds an image."
		load ImageOperations, FileOperations, ExecuteOperations,
			DebianOperations,
			UserOperations, NetworkOperations,
			ExportOperations, TarOperations
	}

	void load(Class<? extends Operations>[] operations) {
		operations.each { owner.metaClass.mixin(it) }
	}

	void customize(Closure c) {
		customizations.add(c)
	}

	// Augeas customizations

	void set(String path, String value) {
		customize {
			GuestFS g = delegate as GuestFS
			g.aug_set("/files$path", value)
		}
	}

	void set(Map<String, String> settings) {
		customize {
			GuestFS g = delegate as GuestFS
			m.each { String k, v ->
				g.aug_set("/files$k", v)
			}
		}
	}

	class Context {
		GuestFS g

		// Makes the project keyword work in operations.
		public Project getProject() {
			return ImageTask.this.getProject()
		}

		public ImageTask getTask() {
			return ImageTask.this
		}

		public GuestFS getGuestFS(Closure c = null) {
			if (g == null) {
				g = fsOpen(c)
				fsInspect(g)
				g.aug_init("/", 0)
			}
			return g
		}

		public File getImageFile() {
			// Assume raw file manipulation.
			close()
			return getTask().getImageFile()
		}

/*
		public QEmu getQEmu() {
			if (g != null) {
				close();
			}
		}
*/

		public void close() {
			if (g != null) {
				try {
					g.aug_match('/augeas//error/message').each {
						println it + " = " + g.aug_get(it)
					}
					g.aug_save()
					g.aug_close()
				} catch (LibGuestFSException e) {
					println "Failed to close augeas: " + e
				}
				fsClose(g)
				g = null;
			}
		}

		// The purpose of this method is to return the
		// relevant resource type to the operation,
		// ensuring that all handles from other resource
		// types have been cleaned up, closed and flushed.
		// This allows the context to cache the guestfs
		// handle between operations.
		public Object asType(Class<?> c) {
			if (GuestFS.class.isAssignableFrom(c))
				return getGuestFS()
			if (File.class.isAssignableFrom(c))
				return getImageFile()
			return super.asType(c)
		}

		/*
		def methodMissing(String name, args) {
			getTask().invokeMethod(name, args)
		}
		*/
	}

	@TaskAction
	// @Override
	void run() {
		Context c = new Context()
		try {
			customizations.each {
				c.with it
			}
		} finally {
			c.close()
		}
	}
}

