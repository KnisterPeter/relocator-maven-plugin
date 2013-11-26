package de.matrixweb.maven.plugins.relocator;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.plexus.util.SelectorUtils;

/**
 * Shameless copy of the SimpleRelocator class from Apaches maven-shade-plugin.
 * 
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
@SuppressWarnings("javadoc")
public class SimpleRelocator implements Relocator {

  private final String pattern;

  private final String pathPattern;

  private final String shadedPattern;

  private final String shadedPathPattern;

  private final Set<String> includes;

  private final Set<String> excludes;

  public SimpleRelocator(final String patt, final String shadedPattern,
      final List<String> includes, final List<String> excludes) {
    if (patt == null) {
      this.pattern = "";
      this.pathPattern = "";
    } else {
      this.pattern = patt.replace('/', '.');
      this.pathPattern = patt.replace('.', '/');
    }

    if (shadedPattern != null) {
      this.shadedPattern = shadedPattern.replace('/', '.');
      this.shadedPathPattern = shadedPattern.replace('.', '/');
    } else {
      this.shadedPattern = "hidden." + this.pattern;
      this.shadedPathPattern = "hidden/" + this.pathPattern;
    }

    this.includes = normalizePatterns(includes);
    this.excludes = normalizePatterns(excludes);
  }

  private static Set<String> normalizePatterns(final Collection<String> patterns) {
    Set<String> normalized = null;

    if (patterns != null && !patterns.isEmpty()) {
      normalized = new LinkedHashSet<String>();

      for (final String pattern : patterns) {

        final String classPattern = pattern.replace('.', '/');

        normalized.add(classPattern);

        if (classPattern.endsWith("/*")) {
          final String packagePattern = classPattern.substring(0,
              classPattern.lastIndexOf('/'));
          normalized.add(packagePattern);
        }
      }
    }

    return normalized;
  }

  private boolean isIncluded(final String path) {
    if (this.includes != null && !this.includes.isEmpty()) {
      for (final String include : this.includes) {
        if (SelectorUtils.matchPath(include, path, true)) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private boolean isExcluded(final String path) {
    if (this.excludes != null && !this.excludes.isEmpty()) {
      for (final String exclude : this.excludes) {
        if (SelectorUtils.matchPath(exclude, path, true)) {
          return true;
        }
      }
    }
    return false;
  }

  public boolean canRelocatePath(String path) {
    if (path.endsWith(".class")) {
      path = path.substring(0, path.length() - 6);
    }

    if (!isIncluded(path) || isExcluded(path)) {
      return false;
    }

    // Allow for annoying option of an extra / on the front of a path. See
    // MSHADE-119; comes from
    // getClass().getResource("/a/b/c.properties").
    return path.startsWith(this.pathPattern)
        || path.startsWith("/" + this.pathPattern);
  }

  public boolean canRelocateClass(final String clazz) {
    return clazz.indexOf('/') < 0 && canRelocatePath(clazz.replace('.', '/'));
  }

  public String relocatePath(final String path) {
    return path.replaceFirst(this.pathPattern, this.shadedPathPattern);
  }

  public String relocateClass(final String clazz) {
    return clazz.replaceFirst(this.pattern, this.shadedPattern);
  }

  public String applyToSourceContent(final String sourceContent) {
    return sourceContent.replaceAll("\\b" + this.pattern, this.shadedPattern);
  }
}
