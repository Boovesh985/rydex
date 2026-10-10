# 1. Record architecture decisions

Date: 2026-10-10 · Status: Accepted

## Context
Rydex is built phase by phase. Decisions need to stay visible after the code that implements them changes.

## Decision
Keep short ADRs (Context / Decision / Consequences) in `docs/adr/`, numbered, never rewritten. A superseded ADR gets a new ADR and its status set to "Superseded by N".

## Consequences
A small cost per decision. Reviewers can see *why*, not just *what*.
