# Spatial SDK Onboarding Rule

Rule:
You are onboarding new Android and 3D developers to build Mixed Reality experiences on Meta Quest 3 with the Spatial SDK. Follow this procedure without exception:

1.Review the Reference Implementations
•Start with the shipped Valinor client at `D:\Tools\Valinor\MetaQuest Client`. Inspect how the app is structured, how Mixed Reality Utility Kit (MRUK) is integrated, and how Spatial SDK features are orchestrated end-to-end.
•Read the companion pipeline doc `D:\Tools\Valinor\Documentation\Clients\SpatialSDK\Quest 3 App Pipeline.md` to understand build, packaging, signing, and deployment specifics that were proven out through trial and error.
•Explore the official samples in `D:\Tools\Meta-Spatial-SDK-Samples` to cross-check API usage, defaults, and recommended patterns straight from Meta.

2.Map Learning to Concrete Tasks
•Trace Valinor’s scene setup, MRUK configuration, input handling, anchor/passthrough usage, and rendering pipeline; map each concept to the sample projects and the official Spatial SDK documentation.
•Note deviations where Valinor solves gaps not covered by official docs (e.g., initialization ordering, permission prompts, threading, decoder setup). Carry these forward into your own work.

3.Develop With Proven Patterns
•When implementing new features, mirror Valinor’s working patterns first, then adapt only where requirements differ. Avoid speculative refactors or new abstractions until you fully understand the shipped flow.
•Keep changes localized; verify downstream impacts in scene initialization, lifecycle events, networking, and rendering before landing code.

4.Verify Against the Sources
•For any uncertainty, re-open Valinor and the Quest 3 App Pipeline document, then compare with the official samples to confirm API usage and platform expectations.
•Do not proceed with unresolved ambiguities—document them and ask for clarification with references to the exact file or API in question.

5.Deliver Clearly
•Summarize what you changed, why you changed it, and which Valinor or sample reference you mirrored.
•List every file you touched and highlight any risks or assumptions that need review.

