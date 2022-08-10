/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

package com.sun.javatest.regtest.exec;

import static java.math.BigDecimal.valueOf;
import static java.nio.file.Path.of;
import static java.util.stream.Collectors.toMap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class Cgroup {
    private static final Path SYS_FS = of("/sys/fs");
    private static final String CGROUP_PROCS = "cgroup.procs";
    private static final String MEMORY_SWAP_MAX = "memory.swap.max";
    private static final String MEMORY_MAX = "memory.max";
    private static final String CGROUP_SUBTREE_CONTROL = "cgroup.subtree_control";

    private static final String SELF = "0";

    private static Stream<String> lines(Path path) {
        try {
            return Files.lines(path, Charset.forName("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    synchronized static void write(Path path, String value, OpenOption... options) {
        try {
            Files.writeString(path, value, Charset.forName("UTF-8"), options);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    //0::/user.slice/user-1000.slice/session-3.scope -> /sys/fs/cgroup/user.slice/user-1000.slice/session-3.scope
    static String groupLocation(String cgroupLine) {
        return "/sys/fs/cgroup" + cgroupLine.split(":")[2];
    }

    static Optional<String> getCgroupByPid(long pid) {
        return lines(of("/proc/" + pid + "/cgroup"))
            .filter(str -> str.charAt(0) == '0') // "v2"
            .findAny()
            .map(Cgroup::groupLocation);
    }

    public static Optional<String> getMyCgroup() {
        return getCgroupByPid(ProcessHandle.current().pid());
    }

    public static Stream<String> run(String... args) {
        try {
            Process p = new ProcessBuilder(args).start();
            p.waitFor();
            return new BufferedReader(new InputStreamReader(p.getInputStream())).lines();
        } catch (Exception e) {
            throw new RuntimeException("failed to run: ", e);
        }
    }

    static long cachedUserId = Long.parseLong(run("id", "-u").findFirst().get());

    public static long getUserId() {
        return cachedUserId;
    }

    public static Optional<String> getUserRootGroup() {
        long id = getUserId();
        String group = "cgroup/user.slice/user-" + id + ".slice/user@" + id + ".service";
        return Files.exists(SYS_FS.resolve(group))
            ? Optional.of(group)
            : Optional.empty();
    }

    public static Path drop(Path p, int i) {
        return p.subpath(i, p.getNameCount());
    }

    public static Optional<Path> createCgroup(long bytes) {
        //getMyCgroup().ifPresent(group -> System.out.println("lkorinth inherited cgroup: " + group));
        return getUserRootGroup().map(group -> {
                Path jtreg = SYS_FS.resolve(group + "/jtreg");
            try {
                Files.createDirectories(jtreg);
                write(jtreg.resolve(CGROUP_SUBTREE_CONTROL), "+memory");
                write(jtreg.resolve(CGROUP_SUBTREE_CONTROL), "+pids");
                Path tempGroup = Files.createTempDirectory(jtreg, "jtreg");
                //                System.out.println("lkorinth creating directory: " + tempGroup.toString() + " with limit: " + bytes);
                write(tempGroup.resolve(MEMORY_MAX), "" + bytes);
                write(tempGroup.resolve(MEMORY_SWAP_MAX), "0");
                //write(tempGroup.resolve(CGROUP_PROCS), SELF);
                return drop(tempGroup, 2);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create cgroup", e);
            }
       });
    }

    public static Optional<Path> createCgroupTool(long bytes) {
        return getUserRootGroup().map(group -> {
                Path newGroup = of(group).resolve("jtreg/jtreg" + UUID.randomUUID().toString());
                run("cgcreate", "mem:" + newGroup);
                run("cgset",
                    "-r", MEMORY_MAX      + "=" + bytes,
                    "-r", MEMORY_SWAP_MAX + "=" + "0");
                return newGroup;
        });
    }

    /*
    record Interval(BigDecimal low, BigDecimal high) {
        BigDecimal midPoint() {
            return low.add(high).divide(valueOf(2));
        }
    };
    */

    static class Interval {
        BigDecimal low, high;
        Interval(BigDecimal low, BigDecimal high) {
            this.low = low;
            this.high = high;
        }

        BigDecimal midPoint() {
            return low.add(high).divide(valueOf(2));
        }
    }

    static void delete(Path p) {
        try {
            Files.delete(p);
        } catch (Exception e) {}
    }

    private static ConcurrentHashMap<String, Long>  readTestMetadata(Path p) {
        return new ConcurrentHashMap<>(lines(p).collect(toMap(s -> s.split("=")[0],
                                                              s -> Long.valueOf(s.split("=")[2]),
                                                              Math::max)));
    }

    public static ConcurrentHashMap<String, Long> memUsage = readTestMetadata(Path.of("/home/lkorinth/bisect"));

    public static int runRestricted(ProcessBuilder builder, long memLimit) {
        Path cgroup = createCgroup(memLimit).orElseThrow();
        try {
            //String originalCgroup = getMyCgroup().orElseThrow();
            ArrayList<String> cgexecCmd = new ArrayList<String>();
            //cgexecCmd.addAll(List.of("cgexec", "-g", "*:" + cgroup.toString(), "--sticky"));
            cgexecCmd.addAll(List.of("cgexec2", cgroup.toString()));
            cgexecCmd.addAll(builder.command());
            ProcessBuilder cgexecPB = new ProcessBuilder(cgexecCmd);
            cgexecPB.environment().clear();
            cgexecPB.environment().putAll(builder.environment());
            //System.out.println("lkorinth start process: " + cgexecPB.command());
            return cgexecPB.start().waitFor();
            //            write(of(originalCgroup).resolve(CGROUP_PROCS), SELF);
        } catch (Exception e) {
            throw new  RuntimeException(e);
        } finally {
            delete(cgroup);
        }
    }

    public static Stream<Interval> bisect(Interval interval, Predicate<BigDecimal> predicate) {
        return Stream.iterate(interval, i -> predicate.test(i.midPoint())
                              ? new Interval(i.low, i.midPoint())
                              : new Interval(i.midPoint(), i.high));
    }

    public static boolean canRunProgramWithLimit(ProcessBuilder builder, long memLimit) {
        try {
            int exitValue = runRestricted(builder, memLimit);
            System.out.println("lkorinth exitValue: " + exitValue);
            return exitValue == 95; // success!
        } catch (Exception e) {
            return false;
        }
    }

    public static long bisect(ProcessBuilder builder, Interval interval) {
        return bisect(interval, memLimit -> canRunProgramWithLimit(builder, memLimit.longValue()))
                .skip(8).findFirst().get().midPoint().longValue();
    }

    // https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html
    // systemd-run --user --scope /bin/bash  will start with cgroup with correct permissions (user@1000.service)
    public static void main(String[] args) {
        ProcessBuilder builder = new ProcessBuilder("stress", "--vm", "1", "--vm-bytes", "1G", "--timeout", "1");
        System.out.println("lkorinth bisect size: " + bisect(builder, new Interval(valueOf(0), valueOf(10_000_000_000L))));
    }

    public static String regressionScriptId(RegressionScript script) {
        return  script.getTestDescription().getRootRelativePath()
            + "#" + script.getTestDescription().getName()
            + "#" + script.getTestDescription().getId();
    }
    //cgexec
}
