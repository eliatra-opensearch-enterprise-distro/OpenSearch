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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;

/**
 * A container for opensearch supported version information used in BWC testing.
 *
 * Parse the Java source file containing the versions declarations and use the known rules to figure out which are all
 * the version the current one is wire and index compatible with.
 * On top of this, figure out which of these are unreleased and provide the branch they can be built from.
 *
 * Note that in this context, currentVersion is the unreleased version this build operates on.
 * At any point in time there will surely be four such unreleased versions being worked on,
 * thus currentVersion will be one of these.
 *
 * Considering:
 * <dl>
 *     <dt>M, M &gt; 0</dt>
 *     <dd>last released major</dd>
 *     <dt>N, N &gt; 0</dt>
 *     <dd>last released minor</dd>
 * </dl>
 *
 * <ul>
 * <li>the unreleased <b>major</b>, M+1.0.0 on the `master` branch</li>
 * <li>the unreleased <b>minor</b>,  M.N.0 on the `M.x` (x is literal) branch</li>
 * <li>the unreleased <b>bugfix</b>, M.N.c (c &gt; 0) on the `M.N` branch</li>
 * <li>the unreleased <b>maintenance</b>, M-1.d.e ( d &gt; 0, e &gt; 0) on the `(M-1).d` branch</li>
 * </ul>
 * In addition to these, there will be a fifth one when a minor reaches feature freeze, we call this the <i>staged</i>
 * version:
 * <ul>
 * <li>the unreleased <b>staged</b>, M.N-2.0 (N &gt; 2) on the `M.(N-2)` branch</li>
 * </ul>
 *
 * Each build is only concerned with versions before it, as those are the ones that need to be tested
 * for backwards compatibility. We never look forward, and don't add forward facing version number to branches of previous
 * version.
 *
 * Each branch has a current version, and expected compatible versions are parsed from the server code's Version` class.
 * We can reliably figure out which the unreleased versions are due to the convention of always adding the next unreleased
 * version number to server in all branches when a version is released.
 * E.x when M.N.c is released M.N.c+1 is added to the Version class mentioned above in all the following branches:
 *  `M.N`, `M.x` and `master` so we can reliably assume that the leafs of the version tree are unreleased.
 * This convention is enforced by checking the versions we consider to be unreleased against an
 * authoritative source (maven central).
 * We are then able to map the unreleased version to branches in git and Gradle projects that are capable of checking
 * out and building them, so we can include these in the testing plan as well.
 */
public class BwcVersions {

    private static final Pattern LINE_PATTERN = Pattern.compile(
        "\\W+public static final (LegacyES)?Version V_(\\d+)_(\\d+)_(\\d+)(_alpha\\d+|_beta\\d+|_rc\\d+|_ee\\d+)? .*"
    );

    public static final BwcVersions EMPTY = new BwcVersions();

    private final Version currentVersion;
    private final Map<Integer, List<Version>> groupByMajor;
    private final Map<Version, UnreleasedVersionInfo> unreleased;

    public class UnreleasedVersionInfo {
        public final Version version;
        public final String branch;
        public final String gradleProjectPath;

        UnreleasedVersionInfo(Version version, String branch, String gradleProjectPath) {
            this.version = version;
            this.branch = branch;
            this.gradleProjectPath = gradleProjectPath;
        }
    }

    public BwcVersions(List<String> versionLines) {
        this(versionLines, Version.fromString(VersionProperties.getOpenSearch()));
    }

    protected BwcVersions(List<String> versionLines, Version currentVersionProperty) {
        this(
            versionLines.stream()
                .map(LINE_PATTERN::matcher)
                .filter(Matcher::matches)
                .map(
                    match -> new Version(
                        Integer.parseInt(match.group(2)),
                        Integer.parseInt(match.group(3)),
                        Integer.parseInt(match.group(4))
                    )
                )
                .collect(Collectors.toCollection(TreeSet::new)),
            currentVersionProperty
        );
    }

    // for testkit tests, until BwcVersions is extracted into an extension
    public BwcVersions(SortedSet<Version> allVersions, Version currentVersionProperty) {
        if (allVersions.isEmpty()) {
            throw new IllegalArgumentException("Could not parse any versions");
        }

        // hack: this is horribly volatile like this entire logic; fix
        currentVersion = allVersions.last();

        groupByMajor = allVersions.stream()
            // We only care about the last 2 majors when it comes to BWC.
            // It might take us time to remove the older ones from versionLines, so we allow them to exist.
            // Adjust the major number since OpenSearch 1.x is released after predecessor version 7.x
            .filter(
                version -> (version.getMajor() == 1 ? 7 : version.getMajor()) > (currentVersion.getMajor() == 1
                    ? 7
                    : currentVersion.getMajor()) - 2
            )
            .collect(Collectors.groupingBy(Version::getMajor, Collectors.toList()));

        assertCurrentVersionMatchesParsed(currentVersionProperty);

        assertNoOlderThanTwoMajors();

        Map<Version, UnreleasedVersionInfo> unreleased = new HashMap<>();
        for (Version unreleasedVersion : getUnreleased()) {
            unreleased.put(
                unreleasedVersion,
                new UnreleasedVersionInfo(unreleasedVersion, getBranchFor(unreleasedVersion), getGradleProjectPathFor(unreleasedVersion))
            );
        }
        this.unreleased = Collections.unmodifiableMap(unreleased);
    }

    private BwcVersions() {
        this.currentVersion = Version.fromString(VersionProperties.getOpenSearch());
        this.unreleased = Collections.emptyMap();
        this.groupByMajor = Collections.emptyMap();
    }

    private void assertNoOlderThanTwoMajors() {
        Set<Integer> majors = groupByMajor.keySet();
        // until OpenSearch 3.0 we will need to carry three major support
        // (1, 7, 6) && (2, 1, 7) since OpenSearch 1.0 === Legacy 7.x
        int numSupportedMajors = (currentVersion.getMajor() < 3) ? 3 : 2;
        if (majors.size() != numSupportedMajors && currentVersion.getMinor() != 0 && currentVersion.getRevision() != 0) {
            throw new IllegalStateException("Expected exactly 2 majors in parsed versions but found: " + majors);
        }
    }

    private void assertCurrentVersionMatchesParsed(Version currentVersionProperty) {
        if (currentVersionProperty.equals(currentVersion) == false) {
            throw new IllegalStateException(
                "Parsed versions latest version does not match the one configured in build properties. "
                    + "Parsed latest version is "
                    + currentVersion
                    + " but the build has "
                    + currentVersionProperty
            );
        }
    }

    /**
      * Returns info about the unreleased version, or {@code null} if the version is released.
      */
    public UnreleasedVersionInfo unreleasedInfo(Version version) {
        return unreleased.get(version);
    }

    public void forPreviousUnreleased(Consumer<UnreleasedVersionInfo> consumer) {
        List<UnreleasedVersionInfo> collect = getUnreleased().stream()
            .filter(version -> version.equals(currentVersion) == false)
            .map(version -> new UnreleasedVersionInfo(version, getBranchFor(version), getGradleProjectPathFor(version)))
            .collect(Collectors.toList());

        collect.forEach(uvi -> consumer.accept(uvi));
    }

    private String getGradleProjectPathFor(Version version) {
        // We have Gradle projects set up to check out and build unreleased versions based on the our branching
        // conventions described in this classes javadoc
        if (version.equals(currentVersion)) {
            return ":distribution";
        }

        Map<Integer, List<Version>> releasedMajorGroupedByMinor = getReleasedMajorGroupedByMinor();

        if (version.getRevision() == 0) {
            List<Version> unreleasedStagedOrMinor = getUnreleased().stream().filter(v -> v.getRevision() == 0).collect(Collectors.toList());
            if (unreleasedStagedOrMinor.size() > 2) {
                if (unreleasedStagedOrMinor.get(unreleasedStagedOrMinor.size() - 2).equals(version)) {
                    return ":distribution:bwc:minor";
                } else {
                    return ":distribution:bwc:staged";
                }
            } else {
                return ":distribution:bwc:minor";
            }
        } else {
            if (releasedMajorGroupedByMinor.getOrDefault(version.getMinor(), emptyList()).contains(version)) {
                return ":distribution:bwc:bugfix";
            } else {
                return ":distribution:bwc:maintenance";
            }
        }
    }

    private String getBranchFor(Version version) {
        // based on the rules described in this classes javadoc, figure out the branch on which an unreleased version
        // lives.
        // We do this based on the Gradle project path because there's a direct correlation, so we dont have to duplicate
        // the logic from there
        switch (getGradleProjectPathFor(version)) {
            case ":distribution":
                return "master";
            case ":distribution:bwc:minor":
                // The .x branch will always point to the latest minor (for that major), so a "minor" project will be on the .x branch
                // unless there is more recent (higher) minor.
                final Version latestInMajor = getLatestVersionByKey(groupByMajor, version.getMajor());
                if (latestInMajor.getMinor() == version.getMinor()) {
                    return version.getMajor() + ".x";
                } else {
                    return version.getMajor() + "." + version.getMinor();
                }
            case ":distribution:bwc:staged":
            case ":distribution:bwc:maintenance":
            case ":distribution:bwc:bugfix":
                return version.getMajor() + "." + version.getMinor();
            default:
                throw new IllegalStateException("Unexpected Gradle project name");
        }
    }

    public List<Version> getUnreleased() {
        List<Version> unreleased = new ArrayList<>();

        // The current version is being worked, is always unreleased
        unreleased.add(currentVersion);

        // No unreleased versions for 1.0.0
        // todo remove this hack
        if (currentVersion.equals(Version.fromString("1.0.0"))) {
            return unmodifiableList(unreleased);
        }

        // the tip of the previous major is unreleased for sure, be it a minor or a bugfix
        if (currentVersion.getMajor() != 1) {
            final Version latestOfPreviousMajor = getLatestVersionByKey(
                this.groupByMajor,
                currentVersion.getMajor() == 1 ? 7 : currentVersion.getMajor() - 1
            );
            unreleased.add(latestOfPreviousMajor);
            if (latestOfPreviousMajor.getRevision() == 0) {
                // if the previous major is a x.y.0 release, then the tip of the minor before that (y-1) is also unreleased
                final Version previousMinor = getLatestInMinor(latestOfPreviousMajor.getMajor(), latestOfPreviousMajor.getMinor() - 1);
                if (previousMinor != null) {
                    unreleased.add(previousMinor);
                }
            }
        }

        final Map<Integer, List<Version>> groupByMinor = getReleasedMajorGroupedByMinor();
        int greatestMinor = groupByMinor.keySet().stream().max(Integer::compareTo).orElse(0);

        // the last bugfix for this minor series is always unreleased
        unreleased.add(getLatestVersionByKey(groupByMinor, greatestMinor));

        if (groupByMinor.get(greatestMinor).size() == 1) {
            // we found an unreleased minor
            unreleased.add(getLatestVersionByKey(groupByMinor, greatestMinor - 1));
            if (groupByMinor.getOrDefault(greatestMinor - 1, emptyList()).size() == 1) {
                // we found that the previous minor is staged but not yet released
                // in this case, the minor before that has a bugfix, should there be such a minor
                if (greatestMinor >= 2) {
                    unreleased.add(getLatestVersionByKey(groupByMinor, greatestMinor - 2));
                }
            }
        }

        return unmodifiableList(unreleased.stream().sorted().distinct().collect(Collectors.toList()));
    }

    private Version getLatestInMinor(int major, int minor) {
        return groupByMajor.get(major).stream().filter(v -> v.getMinor() == minor).max(Version::compareTo).orElse(null);
    }

    private Version getLatestVersionByKey(Map<Integer, List<Version>> groupByMajor, int key) {
        return groupByMajor.getOrDefault(key, emptyList())
            .stream()
            .max(Version::compareTo)
            .orElseThrow(() -> new IllegalStateException("Unexpected number of versions in collection"));
    }

    private Map<Integer, List<Version>> getReleasedMajorGroupedByMinor() {
        int currentMajor = currentVersion.getMajor();
        List<Version> currentMajorVersions = groupByMajor.get(currentMajor);
        List<Version> previousMajorVersions = groupByMajor.get(getPreviousMajor(currentMajor));

        final Map<Integer, List<Version>> groupByMinor;
        if (currentMajorVersions.size() == 1) {
            // Current is an unreleased major: x.0.0 so we have to look for other unreleased versions in the previous major
            groupByMinor = previousMajorVersions.stream().collect(Collectors.groupingBy(Version::getMinor, Collectors.toList()));
        } else {
            groupByMinor = currentMajorVersions.stream().collect(Collectors.groupingBy(Version::getMinor, Collectors.toList()));
        }
        return groupByMinor;
    }

    public void compareToAuthoritative(List<Version> authoritativeReleasedVersions) {
        Set<Version> notReallyReleased = new HashSet<>(getReleased());
        notReallyReleased.removeAll(authoritativeReleasedVersions);
        if (notReallyReleased.isEmpty() == false) {
            throw new IllegalStateException(
                "out-of-date released versions"
                    + "\nFollowing versions are not really released, but the build thinks they are: "
                    + notReallyReleased
            );
        }

        Set<Version> incorrectlyConsideredUnreleased = new HashSet<>(authoritativeReleasedVersions);
        incorrectlyConsideredUnreleased.retainAll(getUnreleased());
        if (incorrectlyConsideredUnreleased.isEmpty() == false) {
            throw new IllegalStateException(
                "out-of-date released versions"
                    + "\nBuild considers versions unreleased, "
                    + "but they are released according to an authoritative source: "
                    + incorrectlyConsideredUnreleased
                    + "\nThe next versions probably needs to be added to Version.java (CURRENT doesn't count)."
            );
        }
    }

    private List<Version> getReleased() {
        List<Version> unreleased = getUnreleased();
        return groupByMajor.values()
            .stream()
            .flatMap(Collection::stream)
            .filter(each -> unreleased.contains(each) == false)
            // this is to make sure we only consider OpenSearch versions
            // TODO remove this filter once legacy ES versions are no longer supported
            .filter(v -> v.onOrAfter("1.0.0"))
            .collect(Collectors.toList());
    }

    public List<Version> getIndexCompatible() {
        int currentMajor = currentVersion.getMajor();
        int prevMajor = getPreviousMajor(currentMajor);
        List<Version> result = Stream.concat(groupByMajor.get(prevMajor).stream(), groupByMajor.get(currentMajor).stream())
            .filter(version -> version.equals(currentVersion) == false)
            .collect(Collectors.toList());
        if (currentMajor == 1) {
            // add 6.x compatible for OpenSearch 1.0.0
            return unmodifiableList(Stream.concat(groupByMajor.get(prevMajor - 1).stream(), result.stream()).collect(Collectors.toList()));
        } else if (currentMajor == 2) {
            // add 7.x compatible for OpenSearch 2.0.0
            return unmodifiableList(Stream.concat(groupByMajor.get(7).stream(), result.stream()).collect(Collectors.toList()));
        }
        return unmodifiableList(result);
    }

    public List<Version> getWireCompatible() {
        List<Version> wireCompat = new ArrayList<>();
        int currentMajor = currentVersion.getMajor();
        int lastMajor = currentMajor == 1 ? 6 : currentMajor == 2 ? 7 : currentMajor - 1;
        List<Version> lastMajorList = groupByMajor.get(lastMajor);
        if (lastMajorList == null) {
            throw new IllegalStateException("Expected to find a list of versions for version: " + lastMajor);
        }
        int minor = lastMajorList.get(lastMajorList.size() - 1).getMinor();
        for (int i = lastMajorList.size() - 1; i > 0 && lastMajorList.get(i).getMinor() == minor; --i) {
            wireCompat.add(lastMajorList.get(i));
        }

        // if current is OpenSearch 1.0.0 add all of the 7.x line:
        if (currentMajor == 1) {
            List<Version> previousMajor = groupByMajor.get(7);
            for (Version v : previousMajor) {
                wireCompat.add(v);
            }
        } else if (currentMajor == 2) {
            // add all of the 1.x line:
            List<Version> previousMajor = groupByMajor.get(1);
            for (Version v : previousMajor) {
                wireCompat.add(v);
            }
        }

        wireCompat.addAll(groupByMajor.get(currentMajor));
        wireCompat.remove(currentVersion);
        wireCompat.sort(Version::compareTo);
        return unmodifiableList(wireCompat);
    }

    public List<Version> getUnreleasedIndexCompatible() {
        List<Version> unreleasedIndexCompatible = new ArrayList<>(getIndexCompatible());
        unreleasedIndexCompatible.retainAll(getUnreleased());
        return unmodifiableList(unreleasedIndexCompatible);
    }

    public List<Version> getUnreleasedWireCompatible() {
        List<Version> unreleasedWireCompatible = new ArrayList<>(getWireCompatible());
        unreleasedWireCompatible.retainAll(getUnreleased());
        return unmodifiableList(unreleasedWireCompatible);
    }

    private int getPreviousMajor(int currentMajor) {
        return currentMajor == 1 ? 7 : currentMajor - 1;
    }

}
