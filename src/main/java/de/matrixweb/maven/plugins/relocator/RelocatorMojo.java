package de.matrixweb.maven.plugins.relocator;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;

/**
 * @author markusw
 */
@Mojo(name = "relocate", defaultPhase = LifecyclePhase.PACKAGE)
public class RelocatorMojo extends AbstractMojo {

  /**
   * The current Maven project.
   */
  @Component
  private MavenProject project;

  @Parameter
  private PackageRelocation[] relocations;

  /**
   * The destination directory for the shaded artifact.
   */
  @Parameter(defaultValue = "${project.build.directory}")
  private File outputDirectory;

  /**
   * @see org.apache.maven.plugin.Mojo#execute()
   */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    final Artifact artifact = this.project.getArtifact();
    final File target = new File(this.outputDirectory, artifact.getArtifactId()
        + "-" + artifact.getVersion() + "."
        + artifact.getArtifactHandler().getExtension());
    getLog().info("Relocating inside " + target);

    try {
      doRelocation(target);
    } catch (final IOException e) {
      throw new MojoFailureException(
          "Failed to relocate classes/resources inside jar", e);
    }
  }

  private void doRelocation(final File target) throws IOException,
      MojoExecutionException {
    final File temp = File.createTempFile("relocation-", ".jar");
    try {
      final FileOutputStream fileOutputStream = new FileOutputStream(temp);
      final JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(
          fileOutputStream));

      final Set<String> resources = new HashSet<String>();

      final List<Relocator> relocators = getRelocators();
      final RelocatorRemapper remapper = new RelocatorRemapper(relocators);
      final JarFile jarFile = new JarFile(target);
      for (final Enumeration<JarEntry> j = jarFile.entries(); j
          .hasMoreElements();) {
        final JarEntry entry = j.nextElement();
        final String name = entry.getName();
        if (!entry.isDirectory()) {
          final String mappedName = remapper.map(name);
          getLog().debug("Remapping " + name + " to " + mappedName);

          final InputStream is = jarFile.getInputStream(entry);
          final int idx = mappedName.lastIndexOf('/');
          if (idx != -1) {
            final String dir = mappedName.substring(0, idx);
            if (!resources.contains(dir)) {
              addDirectory(resources, jos, dir);
            }
          }

          if (name.endsWith(".class")) {
            addRemappedClass(remapper, jos, target, name, is);
          } else {
            if (resources.contains(mappedName)) {
              continue;
            }
            addResource(resources, jos, mappedName, is);
          }

          IOUtil.close(is);
        }
      }
      jarFile.close();
      IOUtil.close(jos);

      FileUtils.copyFile(temp, target);
    } finally {
      temp.delete();
    }
  }

  private List<Relocator> getRelocators() {
    final List<Relocator> relocators = new ArrayList<Relocator>();
    if (this.relocations == null) {
      return relocators;
    }
    for (final PackageRelocation r : this.relocations) {
      relocators.add(new SimpleRelocator(r.getPattern(), r.getShadedPattern(),
          r.getIncludes(), r.getExcludes()));
    }
    return relocators;
  }

  private void addDirectory(final Set<String> resources,
      final JarOutputStream jos, final String name) throws IOException {
    if (name.lastIndexOf('/') > 0) {
      final String parent = name.substring(0, name.lastIndexOf('/'));
      if (!resources.contains(parent)) {
        addDirectory(resources, jos, parent);
      }
    }

    // directory entries must end in "/"
    final JarEntry entry = new JarEntry(name + "/");
    jos.putNextEntry(entry);
    resources.add(name);
  }

  private void addRemappedClass(final RelocatorRemapper remapper,
      final JarOutputStream jos, final File jar, final String name,
      final InputStream is) throws IOException, MojoExecutionException {
    if (!remapper.hasRelocators()) {
      try {
        jos.putNextEntry(new JarEntry(name));
        IOUtil.copy(is, jos);
      } catch (final ZipException e) {
        getLog().debug("We have a duplicate " + name + " in " + jar);
      }
      return;
    }

    final ClassReader cr = new ClassReader(is);

    // We don't pass the ClassReader here. This forces the ClassWriter to
    // rebuild the constant pool. Copying the original constant pool should be
    // avoided because it would keep references to the original class names.
    // This is not a problem at runtime (because these entries in the
    // constant pool are never used), but confuses some tools such as Felix'
    // maven-bundle-plugin that use the constant pool to determine the
    // dependencies of a class.
    final ClassWriter cw = new ClassWriter(0);
    final ClassVisitor cv = new RemappingClassAdapter(cw, remapper);
    try {
      cr.accept(cv, ClassReader.EXPAND_FRAMES);
    } catch (final Throwable ise) {
      throw new MojoExecutionException("Error in ASM processing class " + name,
          ise);
    }
    final byte[] renamedClass = cw.toByteArray();
    // Need to take the .class off for remapping evaluation
    final String mappedName = remapper
        .map(name.substring(0, name.indexOf('.')));
    try {
      // Now we put it back on so the class file is written out with the right
      // extension.
      jos.putNextEntry(new JarEntry(mappedName + ".class"));

      IOUtil.copy(renamedClass, jos);
    } catch (final ZipException e) {
      getLog().debug("We have a duplicate " + mappedName + " in " + jar);
    }
  }

  private void addResource(final Set<String> resources,
      final JarOutputStream jos, final String name, final InputStream is)
      throws IOException {
    jos.putNextEntry(new JarEntry(name));
    IOUtil.copy(is, jos);
    resources.add(name);
  }

  /**
   * Shameless copy of the RelocatorRemapper class from Apaches
   * maven-shade-plugin.
   */
  class RelocatorRemapper extends Remapper {

    private final Pattern classPattern = Pattern.compile("(\\[*)?L(.+);");

    List<Relocator> relocators;

    public RelocatorRemapper(final List<Relocator> relocators) {
      this.relocators = relocators;
    }

    public boolean hasRelocators() {
      return !this.relocators.isEmpty();
    }

    @Override
    public Object mapValue(final Object object) {
      if (object instanceof String) {
        String name = (String) object;
        String value = name;

        String prefix = "";
        String suffix = "";

        final Matcher m = this.classPattern.matcher(name);
        if (m.matches()) {
          prefix = m.group(1) + "L";
          suffix = ";";
          name = m.group(2);
        }

        for (final Relocator r : this.relocators) {
          if (r.canRelocateClass(name)) {
            value = prefix + r.relocateClass(name) + suffix;
            break;
          } else if (r.canRelocatePath(name)) {
            value = prefix + r.relocatePath(name) + suffix;
            break;
          }
        }

        return value;
      }

      return super.mapValue(object);
    }

    @Override
    public String map(String name) {
      String value = name;

      String prefix = "";
      String suffix = "";

      final Matcher m = this.classPattern.matcher(name);
      if (m.matches()) {
        prefix = m.group(1) + "L";
        suffix = ";";
        name = m.group(2);
      }

      for (final Relocator r : this.relocators) {
        if (r.canRelocatePath(name)) {
          value = prefix + r.relocatePath(name) + suffix;
          break;
        }
      }

      return value;
    }

  }

}
