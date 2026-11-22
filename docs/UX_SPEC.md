# Daybook UX Specification

**Status:** Product direction approved; implementation specification  
**Platform:** Android-first  
**Last updated:** 2026-11-22  
**Branch:** `docs/ux-spec-activities-first-calm`

## 1. Product direction

**Product:** Daybook — Your Personal Thinking & Communication Coach  
**Tagline:** Understand faster. Think deeper. Communicate better.

Daybook helps people:
1. Understand information and context faster.
2. Think more deeply, critically, and independently.
3. Communicate thoughts clearly and structurally.

AI is a thinking partner, not a replacement for the user's thinking. It should explain its feedback, challenge assumptions constructively, distinguish evidence from interpretation, and preserve user agency.

### Product areas

- **Notes:** capture, organize, and develop knowledge and thoughts.
- **Meeting Intelligence:** capture and understand information from real conversations.
- **Learning Experience:** improve comprehension and independent thinking.
- **Speaking Coach:** practice communication and learn from evidence-based feedback.

Learning and Speaking are distinct paths inside Practice. Do not force every activity into one pipeline:
- Learning may end after understanding or reflection.
- Speaking may begin from the user's own topic or experience.
- A Note does not need to become a speaking exercise.
- A meeting does not need to become a learning article.

## 2. Approved navigation decisions

### Main navigation

Keep exactly three Android bottom-navigation destinations:

1. Notes
2. Practice
3. Meeting

Do not add a fourth Home tab.

### Launch and tab-restoration behavior

- On the first app launch, when no valid tab preference exists, open **Activities**.
- Activities is the initial landing content, not a fourth bottom-navigation destination.
- After the user selects Notes, Practice, or Meeting, persist that selection.
- On subsequent app launches, restore the last selected main tab.
- If the saved value is missing, invalid, or unsupported, fall back to Activities.
- Do not reset the saved tab merely because the app process was recreated.
- Navigation must not stop, restart, or corrupt an active recording. Recording remains owned by the recording service/repository lifecycle, not by the currently visible tab.
- Scope preference by signed-in account if the app supports account switching; do not leak one account's last-tab preference into another account.

### Practice information architecture

Practice contains two distinct primary paths:
- **Learn:** understand a material, test comprehension, and develop independent thinking.
- **Speaking:** practice explaining an idea, experience, problem, or concept.

Progress and history are supporting destinations, not mandatory steps before practice.

## 3. Activities landing screen

### Purpose

Help the user quickly choose a useful next activity without creating pressure or making the app feel like a productivity dashboard.

### Content hierarchy

1. Brief, calm welcome or prompt.
2. Two primary activities:
   - Learn something
   - Practice speaking
3. Two quick actions:
   - New note
   - Record meeting
4. Recent activity, secondary in visual weight.
5. Existing bottom navigation: Notes, Practice, Meeting.

### Interaction

- Learn something opens the Learning path inside Practice.
- Practice speaking opens the Speaking path inside Practice.
- New note opens a blank Note editor.
- Record meeting opens Meeting and allows recording to start.
- Recent items open their corresponding detail/result screen.
- All four primary actions must be reachable without navigating through nested menus.

### Do not add

- Large dashboard statistics.
- Streaks, daily quotas, or mandatory goals.
- Progress metrics that compete with the primary activities.
- A forced onboarding questionnaire before the user can start.

## 4. Notes

### Purpose

A low-friction thinking space for capturing and developing thoughts.

### List screen

- Keep blank/free-form notes as a first-class use case.
- Provide an obvious New Note action.
- Provide search and clear category/tag filtering.
- Each item should show a readable title, concise content preview, category, and modified time where available.
- Empty state should invite writing without implying the user must choose a template.
- Avoid turning Notes into a rigid form or task manager.

### Editor

- Preserve rich-text formatting and free-form editing.
- Make the selected category visible and persist it with the note.
- Autosave must truthfully reflect server persistence:
  - Saving: a write is pending/in progress.
  - Saved: Firestore confirms successful completion.
  - Failed: the write failed; give the user a retry/recovery path.
- Debounce edits, but protect against stale writes overwriting newer content.
- A newly created note must receive a stable ID before concurrent autosaves can create duplicates.
- Closing the editor while a save is pending must not silently discard the latest content.
- AI actions should be contextual and explain what they will do. They must not interrupt ordinary writing.
- AI output should be reviewable before replacing or appending to the user's original note.
- Keep user-authored content intact unless the user explicitly chooses to apply an AI change.

### Acceptance criteria

- [ ] User can create an empty note and begin writing immediately.
- [ ] Search matches title, body, and category.
- [ ] Category filtering is distinct from free-text search.
- [ ] Changing category persists after closing and reopening the note.
- [ ] Saved status appears only after Firestore confirms success.
- [ ] Failed saves are visible and recoverable.
- [ ] Rapid edits cannot be overwritten by an older delayed save.
- [ ] AI suggestions never replace original content without explicit user action.

## 5. Practice

### Practice home

Present two clearly differentiated primary paths: Learn and Speaking. Explain each in plain language. Show progress/history as a secondary section or action.

Do not combine the two paths into a single mandatory sequence.

### Learning Experience

The intended stages are:

1. **Understand context** — identify the topic, overview, key ideas, terms, and source limitations.
2. **Check understanding** — let the user explain the material in their own words.
3. **Build thinking** — invite the user to form and explain an opinion or reasoning.
4. **Feedback** — distinguish comprehension feedback from thinking feedback, cite evidence from the material/user answer, and ask a useful follow-up.

Requirements:
- Make the current stage and next step understandable.
- Separate factual comprehension from opinion and reasoning.
- Ask a manageable number of questions at a time.
- Do not treat disagreement with a source or model as an incorrect answer.
- Do not infer stable intelligence, personality, or ability from one response.
- Allow the user to stop after understanding or reflection.
- Speaking is an optional continuation, never a forced completion step.
- Preserve the P0 non-persistence constraint unless a separate product decision approves session persistence.

### Speaking Coach

Intended flow:

1. Choose what to train.
2. Understand the prompt/challenge.
3. Record the user's own explanation.
4. Receive feedback with transcript evidence and reasons.
5. See a clearer, better-structured example or revision.
6. Retry if desired.
7. Review progress across attempts.

Requirements:
- Support user-defined topics, not only preset prompts.
- Preserve the original transcript and prior evaluation versions.
- Explain why feedback was given; avoid unexplained scores.
- Give specific and actionable next steps.
- Manual re-evaluation requires user feedback/context.
- Progress patterns must be evidence-based and cautious; do not invent ability scores.
- Speaking Practice remains separate from real meeting recordings.
- AI provider failures must not make the rest of Daybook unusable.

## 6. Meeting Intelligence

### Before recording

- Make the purpose and recording action clear.
- Show a concise, accurate privacy disclosure before audio is uploaded or processed.
- Explain where audio is processed and stored in plain language.
- Do not bury material data handling information in technical settings.

### During recording

Keep the active recording screen minimal:
- Recording title.
- Clear recording indicator and elapsed time.
- Useful audio-level indicator.
- Large Important and Confusing markers.
- A Stop action separated from the markers and protected against accidental taps.

Do not require typing or navigating while the user is participating in a meeting. Preserve the existing recording service, chunking, recovery, upload, STT, and retry pipeline unless a separate technical change is explicitly approved.

### After recording

Present information in a useful order:
1. Summary / overview.
2. Decisions.
3. Action items.
4. Important and Confusing markers with timestamps.
5. Timestamped transcript.
6. Audio playback and technical processing details when needed.

Use progressive disclosure for transcript, playback, processing diagnostics, and less-frequently used actions. Do not put every action and every detail at the same visual priority.

### Privacy and deletion

- UI claims must match actual data flow.
- If audio is uploaded to cloud storage for transcription, never claim that audio stays only on the phone.
- Distinguish local deletion from deletion of cloud audio, transcript, and server metadata.
- Do not present deletion as complete until all relevant storage layers have confirmed deletion.
- Define retention and failure behavior before implementing full recording deletion.
- Keep privacy disclosures understandable and visible at the moment they matter.

## 7. Visual direction: Calm

- Soft neutral surfaces and comfortable contrast.
- Clear typography and readable spacing.
- One restrained accent color.
- Consistent, simple iconography.
- Subtle motion only when it clarifies state or interaction.
- Accessible touch targets, text scaling, and contrast.
- Use cards only when they clarify grouping or choice.

Avoid:
- Dense metric dashboards.
- Excessive accent colors.
- Nested cards and visual clutter.
- Persistent AI labels on every component.
- Streak pressure, guilt-oriented copy, or mandatory goals.
- Technical diagnostics in normal user-facing screens.

## 8. Cross-product interaction principles

1. **Clarity first:** users should understand what a screen is for and what happens next.
2. **Progressive disclosure:** show the essential information first; reveal detail when requested.
3. **Low cognitive load:** avoid asking for information the app can infer safely or does not need.
4. **User agency:** AI suggestions are optional; the user owns decisions and final content.
5. **Contextual AI:** invoke AI when it helps the current task, not as a mandatory layer.
6. **Honest feedback:** distinguish observed evidence, interpretation, and uncertainty.
7. **Consistency:** reuse navigation, status, action, and feedback patterns.
8. **Accessibility:** support readable content and forgiving touch interactions.
9. **Data integrity before polish:** saving, recording, recovery, and deletion must be trustworthy.

## 9. Prioritized implementation sequence

### P0 — Data reliability and privacy foundation

- Fix Notes category persistence.
- Make autosave status reflect confirmed Firestore writes.
- Add protection against stale/out-of-order autosave completion.
- Correct false recording privacy statements and align visible disclosure with actual upload flow.
- Audit recording deletion and cloud retention; agree lifecycle behavior before implementation.

### P1 — Navigation and Activities

- Implement Activities landing screen.
- Implement first-launch Activities fallback.
- Persist and restore last selected main tab on later launches.
- Keep exactly three bottom-navigation tabs.
- Ensure active recording survives navigation and process recreation according to the existing service design.

### P2 — Practice UX

- Separate Learn and Speaking paths visually and semantically.
- Clarify progress/history as supporting.
- Improve stage orientation and feedback readability in Learning.
- Keep Speaking challenge, evidence-based feedback, improved version, retry, and history clear.

### P3 — Notes UX

- Improve list hierarchy, category filtering, previews, and modified-time visibility.
- Localize editor controls and save statuses consistently in Indonesian.
- Make AI actions contextual and explain their effect.

### P4 — Meeting result UX

- Keep the active recording experience minimal.
- Reorganize result screen using progressive disclosure.
- Clarify privacy, processing status, and deletion semantics.

### P5 — Calm visual system

- Apply shared visual tokens and components consistently after core flows are reliable.
- Avoid broad redesign that risks recording reliability.

## 10. Known implementation gaps found during source review

These are code-review findings, not yet implementation changes:

- `Home()` currently initializes the selected tab to index 0 and does not persist the selected tab. Activities is not yet implemented as a landing state.
- Notes category selection is currently local editor state; the autosave snapshot does not include the selected category.
- `VM.autoSave()` starts a Firestore write but marks the note SAVED immediately without awaiting Firestore task completion. It also needs protection against stale completion/status races.
- `RecordingResultScreen.kt` contains a stale KDoc statement claiming audio never leaves the phone. The current `SttWorker` uploads audio to Neon Object Storage for cloud transcription.
- `README.md` and `SYSTEM_FLOW.md` still describe retired planner sections and older architecture. Documentation needs a coordinated update after current flows are verified.
- The current main activity remains highly concentrated in `MainActivity.kt`; modularization should be gradual and should not be mixed into risky recording-pipeline changes.
- Android `RecordingRepository.remove()` currently deletes local recording files and local metadata, but the inspected API exposes no recording-delete endpoint and the object-store adapter exposes no delete operation. A local delete must not be described as full cloud deletion; define and implement an account-scoped deletion lifecycle before promising complete erasure.
- The backend recording pipeline currently retains temporary audio for diagnosis/reprocessing; retention duration and cleanup policy are not yet defined in the inspected code.

## 11. Scope boundaries

- Do not add a fourth main navigation tab.
- Do not force Learning into Speaking.
- Do not force Notes into a template or a Speaking flow.
- Do not change the recording/upload/chunk/STT/retry pipeline as part of a visual-only UX task.
- Do not delete Firestore records or cloud recording assets as part of planner retirement.
- Do not claim a feature is tested on-device merely because CI builds successfully.
- Keep architecture refactoring incremental and behavior-preserving.

## 12. Definition of UX readiness

A screen is ready for implementation when:
- Its purpose and primary action are clear.
- Its information hierarchy is defined.
- Loading, empty, error, success, and recovery states are specified where relevant.
- Privacy and data lifecycle implications are understood.
- Acceptance criteria can be tested.
- The design does not contradict the approved product principles or existing data-integrity constraints.
