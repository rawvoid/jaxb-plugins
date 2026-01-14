/*
 * Copyright 2026 Rawvoid(https://github.com/rawvoid)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

module.exports = async ({github, context}) => {
    const title = context.payload.pull_request.title.toLowerCase();
    const sender = context.payload.sender.login.toLowerCase();
    let label = '';

    if (sender === 'dependabot[bot]' || /^chore(\(deps.*\))?!?:/.test(title)) {
        label = 'dependencies';
    }
    else if (/^feat(\(.*\))?!?:/.test(title)) label = 'feature';
    else if (/^fix(\(.*\))?!?:/.test(title)) label = 'bug';
    else if (/^perf(\(.*\))?!?:/.test(title)) label = 'perf';
    else if (/^refactor(\(.*\))?!?:/.test(title)) label = 'refactor';
    else if (/^docs(\(.*\))?!?:/.test(title)) label = 'docs';
    else if (/^test(\(.*\))?!?:/.test(title)) label = 'test';
    else if (/^chore(\(.*\))?!?:/.test(title)) label = 'chore';
    else if (/^revert(\(.*\))?!?:/.test(title)) label = 'revert';
    else if (/^build(\(.*\))?!?:/.test(title)) label = 'build';
    else if (/^style(\(.*\))?!?:/.test(title)) label = 'style';
    else if (/^ci(\(.*\))?!?:/.test(title)) label = 'ci';

    if (label) {
        const {data: currentLabels} = await github.rest.issues.listLabelsOnIssue({
            owner: context.repo.owner,
            repo: context.repo.repo,
            issue_number: context.issue.number
        });

        if (!currentLabels.some(l => l.name === label)) {
            await github.rest.issues.addLabels({
                owner: context.repo.owner,
                repo: context.repo.repo,
                issue_number: context.issue.number,
                labels: [label]
            });
            console.log(`Successfully added label: ${label}`);
        }
    }
};

