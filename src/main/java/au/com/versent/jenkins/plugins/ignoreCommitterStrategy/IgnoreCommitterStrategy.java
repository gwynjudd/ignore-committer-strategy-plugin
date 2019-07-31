/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Versent
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package au.com.versent.jenkins.plugins.ignoreCommitterStrategy;


import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.scm.ChangeLogParser;
import hudson.scm.SCM;
import hudson.scm.SubversionChangeLogParser;
import hudson.scm.SubversionChangeLogSet;
import jenkins.model.Jenkins;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.*;
import jenkins.scm.impl.subversion.SubversionSCMFileSystem;
import jenkins.scm.impl.subversion.SubversionSCMSource;
import org.kohsuke.stapler.DataBoundConstructor;
import jenkins.branch.BranchBuildStrategy;
import jenkins.branch.BranchBuildStrategyDescriptor;

import java.io.File;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import jenkins.plugins.git.GitSCMFileSystem;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

import hudson.plugins.git.GitChangeLogParser;
import hudson.plugins.git.GitChangeSet;
import org.tmatesoft.svn.core.ISVNLogEntryHandler;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.wc.SVNRevision;

import java.util.logging.Level;

import java.util.Arrays;
import java.util.stream.Collectors;

public class IgnoreCommitterStrategy extends BranchBuildStrategy {
    private static final Logger LOGGER = Logger.getLogger(IgnoreCommitterStrategy.class.getName());
    private final String ignoredAuthors;
    private final Boolean allowBuildIfNotExcludedAuthor;

    @DataBoundConstructor
    public IgnoreCommitterStrategy(String ignoredAuthors, Boolean allowBuildIfNotExcludedAuthor) {
        this.ignoredAuthors = ignoredAuthors;
        this.allowBuildIfNotExcludedAuthor = allowBuildIfNotExcludedAuthor;
    }

    /**
     * Get comma-separated list of ignored commit authors
     *
     * @return comma separated list of ignored authors
     */
    public String getIgnoredAuthors() {
        return ignoredAuthors;
    }

    /**
     * Determine if build is allowed if at least one author in the changeset is not excluded
     * @return indicates if build should be triggered if one of the authors is not in the exclude list
     */
    public Boolean getAllowBuildIfNotExcludedAuthor() { return allowBuildIfNotExcludedAuthor; }

    /**
     * Determine if build is required by checking if any of the commit authors is in the ignore list
     * and/or if changesets with at least one non excluded author are allowed
     * <p>
     * {@inheritDoc}
     *
     * @return true if changeset does not have commits by ignored users or at least one user is not excluded and {allowBuildIfNotExcludedAuthor} is true
     */
    @Override
    public boolean isAutomaticBuild(SCMSource source, SCMHead head, SCMRevision currRevision, SCMRevision prevRevision) {
        boolean isSVN = false;

        SubversionSCMFileSystem.Builder svnBuilder = new SubversionSCMFileSystem.BuilderImpl();
        if (svnBuilder.supports(source)) {
            isSVN = true;
        }

        try {
            SCM scm = source.build(head, currRevision);
            SCMSourceOwner owner = source.getOwner();

            if (owner == null) {
                LOGGER.log(Level.SEVERE, "Error retrieving SCMSourceOwner");
                return true;
            }

            SCMFileSystem fileSystem;
            if (!isSVN) {
                GitSCMFileSystem.Builder gitBuilder = new GitSCMFileSystem.BuilderImpl();
                if (currRevision != null && !(currRevision instanceof AbstractGitSCMSource.SCMRevisionImpl)) {
                    fileSystem = gitBuilder.build(source, head, new AbstractGitSCMSource.SCMRevisionImpl(head, currRevision.toString().substring(0, 40)));
                } else {
                    fileSystem = gitBuilder.build(owner, scm, currRevision);
                }
            } else {
                fileSystem = svnBuilder.build(source, head, currRevision);
            }

            if (fileSystem == null) {
                LOGGER.log(Level.SEVERE, "Error retrieving SCMFileSystem");
                return true;
            }

            List<String> ignoredAuthorsList = Arrays.stream(
                    ignoredAuthors.split(",")).map(e -> e.trim().toLowerCase()).collect(Collectors.toList());
            if (!isSVN) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (prevRevision != null && !(prevRevision instanceof AbstractGitSCMSource.SCMRevisionImpl)) {
                    fileSystem.changesSince(new AbstractGitSCMSource.SCMRevisionImpl(head, prevRevision.toString().substring(0, 40)), out);
                } else {
                    fileSystem.changesSince(prevRevision, out);
                }
                GitChangeLogParser parser = new GitChangeLogParser(true);
                List<GitChangeSet> logs = parser.parse(new ByteArrayInputStream(out.toByteArray()));

                LOGGER.info(String.format("Ignored authors: %s", ignoredAuthorsList.toString()));

                for (GitChangeSet log : logs) {
                    String authorEmail = log.getAuthorEmail().trim().toLowerCase();
                    Boolean isIgnoredAuthor = ignoredAuthorsList.contains(authorEmail);

                    if (isIgnoredAuthor) {
                        if (!allowBuildIfNotExcludedAuthor) {
                            // if author is ignored and changesets with at least one non-excluded author are not allowed
                            LOGGER.info(String.format(
                                    "Changeset contains ignored author %s (%s), and allowBuildIfNotExcludedAuthor is %s, therefore build is not required",
                                    authorEmail, log.getCommitId(), allowBuildIfNotExcludedAuthor));
                            return false;
                        }

                    } else {
                        if (allowBuildIfNotExcludedAuthor) {
                            // if author is not ignored and changesets with at least one non-excluded author are allowed
                            LOGGER.info(String.format(
                                    "Changeset contains non ignored author %s (%s) and allowIfNotExcluded is %s, build is required",
                                    authorEmail, log.getCommitId(), allowBuildIfNotExcludedAuthor));
                            return true;
                        }
                    }
                }
            } else {
                Method getRepository = fileSystem.getClass().getDeclaredMethod("getRepository");
                AccessController.doPrivileged(new PrivilegedAction() {
                    public Object run() {
                        getRepository.setAccessible(true);
                        return null; // nothing to return
                    }
                });
                SVNRepository svnRepository = (SVNRepository)getRepository.invoke(fileSystem);

                int start, end;
                end = Integer.parseInt(currRevision.toString());
                start = Integer.parseInt(prevRevision.toString())+1;

                ArrayList<SVNLogEntry> entries = new ArrayList<>();
                String[] paths = {"."};
                svnRepository.log(paths, start, end, false, false, new ISVNLogEntryHandler() {
                    @Override
                    public void handleLogEntry(SVNLogEntry svnLogEntry) throws SVNException {
                        entries.add(svnLogEntry);
                    }
                });

                for (SVNLogEntry entry: entries) {
                    String authorId = entry.getAuthor().trim().toLowerCase();
                    Boolean isIgnoredAuthor = ignoredAuthorsList.contains(authorId);

                    if (isIgnoredAuthor) {
                        if (!allowBuildIfNotExcludedAuthor) {
                            // if author is ignored and changesets with at least one non-excluded author are not allowed
                            LOGGER.info(String.format(
                                    "Changeset contains ignored author %s (%s), and allowBuildIfNotExcludedAuthor is %s, therefore build is not required",
                                    authorId, entry.getRevision(), allowBuildIfNotExcludedAuthor));
                            return false;
                        }

                    } else {
                        if (allowBuildIfNotExcludedAuthor) {
                            // if author is not ignored and changesets with at least one non-excluded author are allowed
                            LOGGER.info(String.format(
                                    "Changeset contains non ignored author %s (%s) and allowIfNotExcluded is %s, build is required",
                                    authorId, entry.getRevision(), allowBuildIfNotExcludedAuthor));
                            return true;
                        }
                    }
                }
            }


            // here if commits are made by ignored authors and allowBuildIfNotExcludedAuthor is true, in this case return false
            // or if all commits are made by non-ignored authors and allowBuildIfNotExcludedAuthor is false, in this case return true
            LOGGER.info(String.format("All commits in the changeset are made by %s excluded authors, build is %s",
                    allowBuildIfNotExcludedAuthor ? "" : "Non", !allowBuildIfNotExcludedAuthor ));

            return !allowBuildIfNotExcludedAuthor;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Exception", e);
            return true;
        }

    }

    @Extension
    public static class DescriptorImpl extends BranchBuildStrategyDescriptor {
        public String getDisplayName() {
            return "Ignore Committer Strategy";
        }
    }

}
