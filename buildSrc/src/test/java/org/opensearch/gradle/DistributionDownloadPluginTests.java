/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.gradle;

import org.opensearch.gradle.OpenSearchDistribution.Platform;
import org.opensearch.gradle.OpenSearchDistribution.Type;
import org.opensearch.gradle.info.BuildParams;
import org.opensearch.gradle.test.GradleUnitTestCase;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.internal.artifacts.repositories.DefaultIvyArtifactRepository;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Ignore;

import java.io.File;
import java.util.Arrays;
import java.util.TreeSet;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.core.StringContains.containsString;

@Ignore
public class DistributionDownloadPluginTests extends GradleUnitTestCase {
    private static Project rootProject;
    private static Project archivesProject;
    private static Project packagesProject;
    private static Project bwcProject;

    private static final Version BWC_MAJOR_VERSION = Version.fromString("5.0.0");
    private static final Version BWC_MINOR_VERSION = Version.fromString("4.1.0");
    private static final Version BWC_STAGED_VERSION = Version.fromString("4.0.0");
    private static final Version BWC_BUGFIX_VERSION = Version.fromString("4.0.1");
    private static final Version BWC_MAINTENANCE_VERSION = Version.fromString("3.90.1");
    private static final BwcVersions BWC_MINOR = new BwcVersions(
        new TreeSet<>(Arrays.asList(BWC_BUGFIX_VERSION, BWC_MINOR_VERSION, BWC_MAJOR_VERSION)),
        BWC_MAJOR_VERSION
    );
    private static final BwcVersions BWC_STAGED = new BwcVersions(
        new TreeSet<>(Arrays.asList(BWC_STAGED_VERSION, BWC_MINOR_VERSION, BWC_MAJOR_VERSION)),
        BWC_MAJOR_VERSION
    );
    private static final BwcVersions BWC_BUGFIX = new BwcVersions(
        new TreeSet<>(Arrays.asList(BWC_BUGFIX_VERSION, BWC_MINOR_VERSION, BWC_MAJOR_VERSION)),
        BWC_MAJOR_VERSION
    );
    private static final BwcVersions BWC_MAINTENANCE = new BwcVersions(
        new TreeSet<>(Arrays.asList(BWC_MAINTENANCE_VERSION, BWC_STAGED_VERSION, BWC_MINOR_VERSION)),
        BWC_MINOR_VERSION
    );

    public void testVersionDefault() {
        OpenSearchDistribution distro = checkDistro(
            createProject(null, false),
            "testdistro",
            null,
            Type.ARCHIVE,
            Platform.LINUX,
            JavaPackageType.JDK
        );
        assertEquals(distro.getVersion(), VersionProperties.getOpenSearch());
    }

    public void testCustomDistributionUrlWithUrl() {
        Project project = ProjectBuilder.builder().build();
        String customUrl = "https://artifacts.opensearch.org/custom";
        project.getExtensions().getExtraProperties().set("customDistributionUrl", customUrl);
        DistributionDownloadPlugin plugin = new DistributionDownloadPlugin();
        plugin.apply(project);
        assertEquals(4, project.getRepositories().size());
        assertEquals(
            ((DefaultIvyArtifactRepository) project.getRepositories().getAt("opensearch-downloads")).getUrl().toString(),
            customUrl
        );
        assertEquals(
            ((DefaultIvyArtifactRepository) project.getRepositories().getAt("opensearch-snapshots")).getUrl().toString(),
            customUrl
        );
        assertEquals(
            ((DefaultIvyArtifactRepository) project.getRepositories().getAt("elasticsearch-downloads")).getUrl().toString(),
            "https://artifacts-no-kpi.elastic.co"
        );
        assertEquals(
            ((DefaultIvyArtifactRepository) project.getRepositories().getAt("elasticsearch-snapshots")).getUrl().toString(),
            "https://snapshots-no-kpi.elastic.co"
        );

    }

    public void testCustomDistributionUrlWithoutUrl() {
        Project project = ProjectBuilder.builder().build();
        DistributionDownloadPlugin plugin = new DistributionDownloadPlugin();
        plugin.apply(project);
        assertEquals(5, project.getRepositories().size());
        assertEquals(
            ((DefaultIvyArtifactRepository) project.getRepositories().getAt("opensearch-downloads")).getUrl().toString(),
            "https://artifacts.opensearch.org"
        );
        assertEquals(
            ((DefaultIvyArtifactRepository) project.getRepositories().getAt("opensearch-downloads2")).getUrl().toString(),
            "https://artifacts.opensearch.org"
        );
        assertEquals(
            ((DefaultIvyArtifactRepository) project.getRepositories().getAt("opensearch-snapshots")).getUrl().toString(),
            "https://artifacts.opensearch.org"
        );
        assertEquals(
            ((DefaultIvyArtifactRepository) project.getRepositories().getAt("elasticsearch-downloads")).getUrl().toString(),
            "https://artifacts-no-kpi.elastic.co"
        );
        assertEquals(
            ((DefaultIvyArtifactRepository) project.getRepositories().getAt("elasticsearch-snapshots")).getUrl().toString(),
            "https://snapshots-no-kpi.elastic.co"
        );
    }

    public void testBadVersionFormat() {
        assertDistroError(
            createProject(null, false),
            "testdistro",
            "badversion",
            Type.ARCHIVE,
            Platform.LINUX,
            JavaPackageType.JDK,
            "Invalid version format: 'badversion'"
        );
    }

    public void testTypeDefault() {
        OpenSearchDistribution distro = checkDistro(
            createProject(null, false),
            "testdistro",
            "5.0.0",
            null,
            Platform.LINUX,
            JavaPackageType.JDK
        );
        assertEquals(distro.getType(), Type.ARCHIVE);
    }

    public void testPlatformDefault() {
        OpenSearchDistribution distro = checkDistro(
            createProject(null, false),
            "testdistro",
            "5.0.0",
            Type.ARCHIVE,
            null,
            JavaPackageType.JDK
        );
        assertEquals(distro.getPlatform(), OpenSearchDistribution.CURRENT_PLATFORM);
    }

    public void testPlatformForIntegTest() {
        assertDistroError(
            createProject(null, false),
            "testdistro",
            "5.0.0",
            Type.INTEG_TEST_ZIP,
            Platform.LINUX,
            null,
            "platform cannot be set on opensearch distribution [testdistro]"
        );
    }

    public void testBundledJdkDefault() {
        OpenSearchDistribution distro = checkDistro(
            createProject(null, false),
            "testdistro",
            "5.0.0",
            Type.ARCHIVE,
            Platform.LINUX,
            JavaPackageType.JDK
        );
        assertThat(distro.getBundledJdk(), equalTo(JavaPackageType.JDK));
    }

    public void testBundledJdkForIntegTest() {
        assertDistroError(
            createProject(null, false),
            "testdistro",
            "5.0.0",
            Type.INTEG_TEST_ZIP,
            null,
            JavaPackageType.JDK,
            "bundledJdk cannot be set on opensearch distribution [testdistro]"
        );
    }

    public void testLocalCurrentVersionIntegTestZip() {
        Project project = createProject(BWC_MINOR, true);
        Project archiveProject = ProjectBuilder.builder().withParent(archivesProject).withName("integ-test-zip").build();
        archiveProject.getConfigurations().create("default");
        archiveProject.getArtifacts().add("default", new File("doesnotmatter"));
        createDistro(project, "distro", VersionProperties.getOpenSearch(), Type.INTEG_TEST_ZIP, null, null);
        checkPlugin(project);
    }

    public void testLocalCurrentVersionArchives() {
        for (Platform platform : Platform.values()) {
            for (JavaPackageType bundledJdk : JavaPackageType.values()) {
                for (Architecture architecture : Architecture.values()) {
                    // create a new project in each iteration, so that we know we are resolving the only additional project being created
                    Project project = createProject(BWC_MINOR, true);
                    String projectName = projectName(platform.toString(), bundledJdk);
                    projectName += (platform == Platform.WINDOWS ? "-zip" : "-tar");
                    Project archiveProject = ProjectBuilder.builder().withParent(archivesProject).withName(projectName).build();
                    archiveProject.getConfigurations().create("default");
                    archiveProject.getArtifacts().add("default", new File("doesnotmatter"));
                    final OpenSearchDistribution distro = createDistro(
                        project,
                        "distro",
                        VersionProperties.getOpenSearch(),
                        Type.ARCHIVE,
                        platform,
                        bundledJdk
                    );
                    distro.setArchitecture(architecture);
                    checkPlugin(project);
                }
            }
        }
    }

    public void testLocalCurrentVersionPackages() {
        for (Type packageType : new Type[] { Type.RPM, Type.DEB }) {
            for (JavaPackageType bundledJdk : JavaPackageType.values()) {
                Project project = createProject(BWC_MINOR, true);
                String projectName = projectName(packageType.toString(), bundledJdk);
                Project packageProject = ProjectBuilder.builder().withParent(packagesProject).withName(projectName).build();
                packageProject.getConfigurations().create("default");
                packageProject.getArtifacts().add("default", new File("doesnotmatter"));
                createDistro(project, "distro", VersionProperties.getOpenSearch(), packageType, null, bundledJdk);
                checkPlugin(project);
            }
        }
    }

    public void testLocalBwcArchives() {
        for (Platform platform : Platform.values()) {
            // note: no non bundled jdk for bwc
            String configName = projectName(platform.toString(), JavaPackageType.JDK);
            configName += (platform == Platform.WINDOWS ? "-zip" : "-tar");

            checkBwc("minor", configName, BWC_MINOR_VERSION, Type.ARCHIVE, platform, BWC_MINOR, true);
            checkBwc("staged", configName, BWC_STAGED_VERSION, Type.ARCHIVE, platform, BWC_STAGED, true);
            checkBwc("bugfix", configName, BWC_BUGFIX_VERSION, Type.ARCHIVE, platform, BWC_BUGFIX, true);
            checkBwc("maintenance", configName, BWC_MAINTENANCE_VERSION, Type.ARCHIVE, platform, BWC_MAINTENANCE, true);
        }
    }

    public void testLocalBwcPackages() {
        for (Type packageType : new Type[] { Type.RPM, Type.DEB }) {
            // note: no non bundled jdk for bwc
            String configName = projectName(packageType.toString(), JavaPackageType.JDK);

            checkBwc("minor", configName, BWC_MINOR_VERSION, packageType, null, BWC_MINOR, true);
            checkBwc("staged", configName, BWC_STAGED_VERSION, packageType, null, BWC_STAGED, true);
            checkBwc("bugfix", configName, BWC_BUGFIX_VERSION, packageType, null, BWC_BUGFIX, true);
            checkBwc("maintenance", configName, BWC_MAINTENANCE_VERSION, packageType, null, BWC_MAINTENANCE, true);
        }
    }

    private void assertDistroError(
        Project project,
        String name,
        String version,
        Type type,
        Platform platform,
        JavaPackageType bundledJdk,
        String message
    ) {
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> checkDistro(project, name, version, type, platform, bundledJdk)
        );
        assertThat(e.getMessage(), containsString(message));
    }

    private OpenSearchDistribution createDistro(
        Project project,
        String name,
        String version,
        Type type,
        Platform platform,
        JavaPackageType bundledJdk
    ) {
        NamedDomainObjectContainer<OpenSearchDistribution> distros = DistributionDownloadPlugin.getContainer(project);
        return distros.create(name, distro -> {
            if (version != null) {
                distro.setVersion(version);
            }
            if (type != null) {
                distro.setType(type);
            }
            if (platform != null) {
                distro.setPlatform(platform);
            }
            if (bundledJdk != null) {
                distro.setBundledJdk(bundledJdk);
            }
        });
    }

    // create a distro and finalize its configuration
    private OpenSearchDistribution checkDistro(
        Project project,
        String name,
        String version,
        Type type,
        Platform platform,
        JavaPackageType bundledJdk
    ) {
        OpenSearchDistribution distribution = createDistro(project, name, version, type, platform, bundledJdk);
        distribution.finalizeValues();
        return distribution;
    }

    // check the download plugin can be fully configured
    private void checkPlugin(Project project) {
        DistributionDownloadPlugin plugin = project.getPlugins().getPlugin(DistributionDownloadPlugin.class);
        plugin.setupDistributions(project);
    }

    private void checkBwc(
        String projectName,
        String config,
        Version version,
        Type type,
        Platform platform,
        BwcVersions bwcVersions,
        boolean isInternal
    ) {
        Project project = createProject(bwcVersions, isInternal);
        Project archiveProject = ProjectBuilder.builder().withParent(bwcProject).withName(projectName).build();
        archiveProject.getConfigurations().create(config);
        archiveProject.getArtifacts().add(config, new File("doesnotmatter"));
        final OpenSearchDistribution distro = createDistro(project, "distro", version.toString(), type, platform, JavaPackageType.JDK);
        distro.setArchitecture(Architecture.current());
        checkPlugin(project);
    }

    private Project createProject(BwcVersions bwcVersions, boolean isInternal) {
        rootProject = ProjectBuilder.builder().build();
        BuildParams.init(params -> params.setIsInternal(isInternal));
        Project distributionProject = ProjectBuilder.builder().withParent(rootProject).withName("distribution").build();
        archivesProject = ProjectBuilder.builder().withParent(distributionProject).withName("archives").build();
        packagesProject = ProjectBuilder.builder().withParent(distributionProject).withName("packages").build();
        bwcProject = ProjectBuilder.builder().withParent(distributionProject).withName("bwc").build();
        Project project = ProjectBuilder.builder().withParent(rootProject).build();
        if (bwcVersions != null) {
            project.getExtensions().getExtraProperties().set("bwcVersions", bwcVersions);
        }
        project.getPlugins().apply("opensearch.distribution-download");
        return project;
    }

    private static String projectName(String base, JavaPackageType bundledJdk) {
        return (bundledJdk == JavaPackageType.JDK) ? base : ((bundledJdk == JavaPackageType.NONE) ? ("no-jdk-" + base) : "jre-" + base);
    }
}
