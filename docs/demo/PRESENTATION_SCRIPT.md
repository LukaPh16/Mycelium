# Mycelium: 4-minute presentation (3 speakers)

A screen recording of the app plays in the background for the whole 4 minutes. Each part has the words to say
(~140 words per minute) and what the background video should show at that moment.

| Part | Speaker | Topic | Time |
|---|---|---|---|
| 1 | Speaker 1 | Overview: what Mycelium is | 0:00 – 1:15 |
| 2 | Speaker 2 | The UI: the terrarium and the screens | 1:15 – 2:40 |
| 3 | Speaker 3 | Why we built it: the problem, what we solved, privacy | 2:40 – 4:00 |

---

## Part 1: Overview (Speaker 1, 0:00 – 1:15)

**Background video:** app opening (splash with the pixel mushroom) → the terrarium at full screen → slow zoom on a
creature with a Snoop and the red threads going up to the lid.

> Hi, we're [team name], and this is **Mycelium**.
>
> Every app on your phone talks to the internet, and a lot of that talk isn't for you. It's for trackers and ad
> networks that build a profile of you to feed your feed. You can't see any of it.
>
> Mycelium makes it visible. You pick the apps you want to watch, like TikTok, Instagram or a food delivery app.
> Mycelium notices every company those apps contact, recognizes which ones are trackers, and explains it in plain
> words.
>
> And instead of a boring table, every app becomes a little creature living in a glass terrarium. Trackers show up
> as pests we call **Snoops** that latch onto the creature. The more an app feeds trackers, the sicker its creature
> looks.
>
> The most important part: **everything is kept and processed on your phone.** No accounts, no servers of ours.
> Speaker 2 will show you around.

---

## Part 2: The UI (Speaker 2, 1:15 – 2:40)

**Background video, in order:**
1. 1:15 – Terrarium: creatures on moss, islands, pond; tap a creature (bounce + hello sound); bottom sheet pulled up
   to show the creature card (stage, growth bar, mood).
2. 1:35 – Tap a Snoop → "Shoo away" → poof, sparkles, hearts, creature heals.
3. 1:50 – Settings > Weather: switch to Storm (lightning + rain), then Snow + Winter, then Autumn (amber tree).
4. 2:05 – Evolution moment (rays, flicker, fanfare, "Lovely!").
5. 2:15 – Ask the Gardener: ask "What just happened?"; then Insights cards and the Busy hours chart.
6. 2:30 – My Apps list with big switches.

> The interface was designed with **Lovable**: our design team prototyped the look and flow there, and we brought it
> to life natively on Android in a cozy pixel-art style.
>
> This is the **terrarium**. Each watched app is one creature, from 25 creature types, with its own colors and hat.
> Creatures grow through **eight stages**, from Seedling to Legend, and they have moods: happy, grumpy when a new
> Snoop arrives, sick when trackers take over.
>
> Tap a Snoop and **shoo it away**. That really blocks the tracker for that app, and the creature heals.
>
> The jar lives on a windowsill. The window shows the real time of day, with the sun and the moon in its real phase,
> plus weather and seasons: rain, storms, snow, autumn leaves. Every creature, Snoop and animation has its own
> original sound.
>
> When a creature reaches a new stage, it **evolves**.
>
> You can also just ask the **Gardener**, our chatbot: "What just happened?" It answers from your phone's own data
> and links every fact to the moment it happened. **Insights** sums up your day or week.

---

## Part 3: Why we built it (Speaker 3, 2:40 – 4:00)

**Background video:** Activity timeline cards ("A Snoop came by: Braze, 2 min ago") → Insights (TikTok 165
contacts, Instagram 10 Snoop visits) → "What Mycelium can't see" page → About & Privacy → back to the terrarium,
a happy healed creature, slow zoom out to the whole jar to end.

> Why does this matter? Tracking is invisible. Privacy policies are long, and "allow" is one tap. People don't change
> what they can't see.
>
> When we tested Mycelium on a real phone, in one afternoon **TikTok made 165 contacts**, **Instagram had 10 visits
> from Meta's trackers**, and a food delivery app was talking to **Braze and Adjust**, companies most people have
> never heard of.
>
> So we solved three things. **Visibility**: you see which app contacts which tracker, live. **Understanding**:
> plain words, a creature that gets sick, and a Gardener that explains. **Action**: one tap shoos a tracker away.
>
> And we did it without becoming another tracker ourselves. Mycelium uses Android's VPN feature only **locally**:
> capture, classification, stats and alerts all run **on the device**. The optional AI helper uses your own key and
> only sends a short summary you can inspect. We're also honest about limits: we see website names, never content,
> and we don't claim to stop all tracking.
>
> Under the hood it's native Kotlin with a full engine API, so any interface, including our Lovable designs, can
> plug in.
>
> **Mycelium: see who your apps are really talking to, and take your garden back.** Thank you!

---

## Tips
- Rehearse with a timer; each part is about 170–190 words. Cut a sentence rather than rushing.
- Prepare the phone: watch 2–3 apps and use them beforehand so there are real Snoops and numbers.
- Set Weather/Season in Settings before recording, or use the debug commands in `DEMO_VIDEO_PROMPT.md`.
- Keep the background video on loop so it never ends early; mute app sounds if they clash with the voices.
