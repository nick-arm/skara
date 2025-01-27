/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
 */
package org.openjdk.skara.bots.notify;

import org.openjdk.skara.bot.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.mailinglist.MailingListServerFactory;
import org.openjdk.skara.storage.StorageBuilder;
import org.openjdk.skara.vcs.Tag;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JNotifyBotFactory implements BotFactory {
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots");;

    @Override
    public String name() {
        return "notify";
    }

    @Override
    public List<Bot> create(BotConfiguration configuration) {
        var ret = new ArrayList<Bot>();
        var specific = configuration.specific();

        var database = specific.get("database").asObject();
        var databaseRepo = configuration.repository(database.get("repository").asString());
        var databaseRef = configuration.repositoryRef(database.get("repository").asString());
        var databaseName = database.get("name").asString();
        var databaseEmail = database.get("email").asString();

        for (var repo : specific.get("repositories").fields()) {
            var repoName = repo.name();
            var branchPattern = Pattern.compile("^master$");
            if (repo.value().contains("branches")) {
                branchPattern = Pattern.compile(repo.value().get("branches").asString());
            }

            var includeBranchNames = false;
            if (repo.value().contains("branchnames")) {
                includeBranchNames = repo.value().get("branchnames").asBoolean();
            }

            var updaters = new ArrayList<UpdateConsumer>();
            if (repo.value().contains("json")) {
                var folder = repo.value().get("folder").asString();
                var build = repo.value().get("build").asString();
                var version = repo.value().get("version").asString();
                updaters.add(new JsonUpdater(Path.of(folder), version, build));
            }
            if (repo.value().contains("mailinglists")) {
                var email = specific.get("email").asObject();
                var smtp = email.get("smtp").asString();
                var sender = EmailAddress.parse(email.get("sender").asString());
                var archive = URIBuilder.base(email.get("archive").asString()).build();
                var interval = email.contains("interval") ? Duration.parse(email.get("interval").asString()) : Duration.ofSeconds(1);
                var listServer = MailingListServerFactory.createMailmanServer(archive, smtp, interval);

                for (var mailinglist : repo.value().get("mailinglists").asArray()) {
                    var recipient = mailinglist.get("recipient").asString();
                    var recipientAddress = EmailAddress.parse(recipient);

                    var mode = MailingListUpdater.Mode.ALL;
                    if (mailinglist.contains("mode")) {
                        switch (mailinglist.get("mode").asString()) {
                            case "pr":
                                mode = MailingListUpdater.Mode.PR;
                                break;
                            case "pr-only":
                                mode = MailingListUpdater.Mode.PR_ONLY;
                                break;
                            default:
                                throw new RuntimeException("Unknown mode");
                        }
                    }

                    Map<String, String> headers = mailinglist.contains("headers") ?
                            mailinglist.get("headers").fields().stream()
                                       .collect(Collectors.toMap(JSONObject.Field::name, field -> field.value().asString())) :
                            Map.of();
                    var author = mailinglist.contains("author") ? EmailAddress.parse(mailinglist.get("author").asString()) : null;
                    var allowedDomains = author == null ? Pattern.compile(mailinglist.get("domains").asString()) : null;
                    updaters.add(new MailingListUpdater(listServer.getList(recipient), recipientAddress, sender, author,
                                                        includeBranchNames, mode, headers, allowedDomains));
                }
            }

            if (updaters.isEmpty()) {
                log.warning("No consumers configured for notify bot repository: " + repoName);
                continue;
            }

            var baseName = repo.value().contains("basename") ? repo.value().get("basename").asString() : configuration.repositoryName(repoName);

            var tagStorageBuilder = new StorageBuilder<Tag>(baseName + ".tags.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added tag for " + repoName);
            var branchStorageBuilder = new StorageBuilder<ResolvedBranch>(baseName + ".branches.txt")
                    .remoteRepository(databaseRepo, databaseRef, databaseName, databaseEmail, "Added branch hash for " + repoName);
            var bot = new JNotifyBot(configuration.repository(repoName), configuration.storageFolder(), branchPattern, tagStorageBuilder, branchStorageBuilder, updaters);
            ret.add(bot);
        }

        return ret;
    }
}
