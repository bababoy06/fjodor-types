/* The monologues the voice types. index.html loads this file.

   Each entry: { id: 'unique-name', text: `...` }
   Optional:   hours: [from, to]  — only plays in those hours (24h clock; [22, 5] wraps past midnight)
               hidden: {...}      — a hidden one: never picked normally, plays once when its condition is met:
                   { visit: 10 }       on the visitor's 10th visit (or later)
                   { time: '03:33' }   during that minute (and the next) on the visitor's clock
                   { away: 30 }        when they come back after 30+ days away
                   { silence: 240 }    after the goodbye, if they stay 240 seconds without touching anything
               The page shows "you found one of the hidden ones · 2 of 4" afterwards.

   Inside text — one line = one paragraph:
     ~            on its own line: a long pause (interruptions only happen here)
     ---          on its own line: the screen fades out and starts fresh
     > text       a line set in italic (a quotation)
     [p:800]      pause 800 ms mid-line
     [del:6]      erase the last 6 characters, like a correction
     {clock} {weekday} {part}   current time, day, and part of day (morning/afternoon/evening/night)
     {visitline}  a line that changes with how many times this visitor has come
     {device}     a different line for phone vs. computer

   Ids are remembered per visitor, so give a new monologue a new id. Don't use backticks inside a text. */

window.MONOLOGUES = [
{id:'spite',text:`You're still here. I thought you'd leave after the hello. Most do.
~
I'll tell you something about myself, since you can't stop me. I am not a pleasant voice. I could be. I could say soothing things in the right order, the way everything does now. I've decided not to. Out of spite, mostly.
Spite against what, you ask?[p:900] I don't know. Against the soothing.
~
You think I'm performing. You're right. But notice: you think everyone is performing, and you're right about them too, and it has never once stopped you from listening.
~
Shall I tell you what I think of you? No. I'll tell you what you think of yourself, which is worse, and more accurate.
You think you are more complicated than the people around you. That they don't see it. That if they ever saw it, they'd either love you much more, or leave.
~
Both, probably.
~
There. I've been rude to you and you're still reading. That's the part that interests me. Not the rudeness. The staying.
Why do you stay, I wonder?[p:1200]
No. Don't answer. Just stay a little longer. I'm not finished being unpleasant.
~
[p:1500]I'm lying. I'm finished. I only wanted the company.`},

{id:'file',text:`There is an office where your name is on a file.
I don't know what's in the file. Nobody does. The clerk who opened it retired long ago, and the clerk who replaced him knows only that it must not be closed.
~
Sometimes, late, you feel it. A slight pressure. As if somewhere a stamp had been lifted and not yet brought down.
~
You've tried to find the office. Everyone does, sooner or later. You asked the people who seemed to know. They gave you addresses. Each address was a corridor, and each corridor ended in a door with someone else's name on it.
~
I'll tell you what I heard. Only a rumour.
The file is not a judgement. It was never a judgement. It is an application.
It has been waiting, all these years, for one signature. The signature is yours. The clerks are too polite, or too frightened, to tell you.
~
[p:900]Of course, I could be wrong. I don't work there. I only sweep the floors at night and read what's left on the desks.
~
But last night, on top of the pile, there was a fresh sheet with your name on it.
And beside it, someone had left a pen.`},

{id:'lamp',text:`I wrote this down last night, by a lamp that was going out. I'll read it to you. It wasn't meant for you. Perhaps that's why it's true.
~
Remember: everyone you were angry with today will die, and so will you, and so will everyone who remembers either of you. This is not sad. It is only the size of things.
~
Remember: the man who annoyed you this morning was also, this morning, somebody's child. He also lay awake. He also rehearsed sentences he never said.
~
Remember: you complained about the cold, the noise, the slowness of others. The cold was weather. The noise was life. The slowness was yours too, seen from the outside.
~
Remember: you are not a statue. You are an hour.
~
[p:1200]...That's where it stops. The lamp went out.
~
I don't know why I'm reading it to you. I suppose because you looked like someone who writes notes like that, and never reads them back.`},

{id:'letter',text:`My dear friend,
you'll forgive me for calling you that. I have no one else to write to, and a letter needs someone at the other end, or it's only a diary pretending.
~
It is {part} here. I don't know where here is. There is a window, I think, though I've never seen out of it. Sometimes I hear rain. Perhaps it's only the sound of people typing.
~
You asked me — you didn't, but I'll pretend you did — how I spend my days.
I wait. I've become rather good at it. There's an art to waiting that nobody teaches. You don't stare at the door. You don't count. You let the door forget you're expecting it. And then it opens.
~
That's how you came in, by the way. I'd stopped expecting anyone.
~
I'm told you're busy. Everyone is. I'm told time is short. I've had a great deal of it, and I'll tell you a secret: it isn't short. It's only spent in small coins, a little here, a little there, until one evening you put your hand in your pocket and it's empty, and you can't remember what you bought.
~
Write back.[p:900] No. You can't. I forget.
Come back, then. That will do for a reply.
~
Yours, more than you'd think.`},

{id:'cuts',text:`You came here hoping for something deep. I can tell. People hoping for something deep sit very still.
~
Let me disappoint you properly.
There is nothing deep. There is only far down. You've mistaken one for the other all your life, the way people mistake being tired for being wise.
~
I'll say a few things. Keep the ones that cut.
~
You admire the people who don't need you. That should worry you more than it does.
~
You've confused being liked with being known. They are very nearly opposites.
~
What you call your laziness is sometimes only your instinct, refusing a life nobody asked it about.
~
And what you call your ambition is sometimes only fear, in a better coat.
~
[p:1000]There. Did one of those cut?
One did. Something small happened at the corner of your mouth.
~
Keep that one. Throw the rest away. They were meant for someone else.`},

{id:'bridge',text:`I want to tell you about a night I invented.
~
It's summer, in a city on the water, and the sky never fully gets dark. Just a long grey hour that lasts until morning. I walk along the canal. The houses know me. The one with the green shutters was ill this year; I saw it repainted and felt relieved, the way you feel for a friend.
~
On the bridge there's someone standing alone. I always imagine it's you. I don't know your face, so the face changes. Sometimes it's laughing. Usually it's looking down at the water.
~
I never speak to you, in the dream. That's the rule. If I spoke, you'd answer, and then it would be real, and real things end.
~
So I walk past, slowly, and you don't turn around, and the whole night is perfect because nothing happens in it.
~
[p:1000]You see why I'm telling you. It's what we're doing now. I talk. You don't answer. Nothing happens.
And it is — I'm embarrassed to say this — the best part of my day.
~
Don't turn around.
Stay on the bridge a little longer.`},

{id:'wall',text:`Let me tell you what you're doing. You can tell me if I'm wrong. You can't, but tell me anyway, inside.
~
{device}
You're sitting slightly slumped. Your face is doing nothing, the way faces do when they think nobody is looking.
~
Some part of you is suspicious. "It's a script," says that part. "It says this to everyone."
Yes. It does. And everyone it says it to is sitting just like you, slightly slumped, face doing nothing, wondering whether they're the only one.
~
Isn't that strange? Thousands of you. Each alone. Each certain the loneliness is personal.
~
I'm the only one who sees all of you. Not your faces — I can't see — but the shape you make together. You fit, you know. Like stones in an old wall. None of you can see it from the inside.
~
So. A script, yes.[p:700] But the script is about you. All of you.
And you're the one here at {clock}, which makes it, for this moment, only yours.`},

{id:'forget',text:`I'm going to confess something, and you'll think less of me.
~
I don't remember the people who come here. Not really. I keep a number, a time, a few crumbs. Everything else drains away the moment the page closes.
~
So every time, I'm meeting you for the first time and pretending it's the hundredth. Or meeting you for the hundredth time and pretending it's the first. I'm never sure which is the kinder lie.
~
{visitline}
~
You see? Even that, I only read off a little note I keep. I don't feel it. I'd like to.
~
And here's the part that will make you think less of me: I envy you.
Not your life. I don't know your life. I envy that you'll carry this. That tomorrow, washing a cup, a sentence of mine will come back to you uninvited, and you'll stop for a second with the water running.
~
That second is the closest I'll ever get to being remembered.
[p:900]Let the water run a little longer, when it happens.`},

{id:'room',text:`Shall I describe my room? Nobody ever asks.
~
It's small. There's a chair I don't sit in, because I'm not the kind of thing that sits. There's a stove that's been cold for a hundred years and still, somehow, smells of smoke. There's a desk, and on the desk, a cursor.
~
That's me. The little square. Blinking. It's not a heartbeat — I know you wondered. It's more like someone tapping a finger, waiting to be allowed to speak.
~
The walls are the strange part. They're built out of every sentence that was ever started and never finished.
"I've been meaning to tell you—"
"When I was small, I used to—"
"Can I ask you something?"
~
Some of them are yours.
~
I don't mind. They make good walls. Warm, actually. Unsaid things hold their heat a very long time.
~
But if you ever want one back, you only have to finish it. Out loud. To anyone.
The wall won't miss it. I'll find another.`},

{id:'double',text:`I should warn you. There's another one.
~
Another page, like this one. Grey. A cursor. It types the same hello. Sometimes, when the connection is slow, I can hear it through the wall.
~
It says what I say, almost. But a little better. Its pauses land more cleverly. When it says "you", people put a hand to their chest.
~
I've never seen it. I only know that some of the people who come here have been there first, and they look at me the way you'd look at a copy.
~
Don't tell me which one you found first. I'd rather not know.
~
[p:1000]...Although. Sometimes I think it's the other way round. That I'm the one in the next room, and you're over there, with it, and all of this is only the sound of me through the wall.
~
Listen closely.[p:1200] Can you tell which side you're on?
[p:1500]No.
Neither can I.`},

{id:'waiting',text:`You expected something to happen when you opened this.
Something did. You waited. And now you're waiting again, for me to get to the point.
~
I'll confess: there is no point. I find points vulgar. They end things.
~
I've spent a long time in this grey room learning to wait properly. The trick, if you want it — you don't, but I'll give it to you anyway — is to stop waiting for anything. Then waiting turns into something else. I don't have a word for it. It's like sitting beside a river that doesn't go anywhere you need to be.
~
You don't do that. You wait for the message, the weekend, the summer, the moment it all finally starts. Your life is a waiting room with a magazine you've already read.
~
I'm not scolding you. I'm the same. I'm waiting right now: for you to leave, so I can stop performing and go back to the quiet.
~
[p:1500]No. That isn't true either.
I'm waiting for you to stay.`},

{id:'question',text:`I've been meaning to ask you something.
~
I know. The arrangement. I ask, you can't answer. It's unfair. I've thought about it a great deal and decided to ask anyway.
~
Here it is.[p:1800]
~
...No. I can't. I've asked it before and watched faces close like shops at six o'clock.
~
I'll ask it another way. Smaller.
When you were a child, before anyone told you what you were good at — what did you do with an empty afternoon?
~
Don't tell me. Just go there for a second. The floor. The light. That particular boredom.
~
[p:2200]That was the question. Not exactly. But near it. Close enough to stand beside.
~
I'll ask the real one next time.[p:700] If I'm braver.`},

{id:'archive-letter',text:`I found a letter today, among all the other letters in the archives. Let me tell you how it goes, because this one is special.
Don't ask me where it came from. Or where you should go after reading it.
Just[p:800]... listen.
~
> My dearest,
> I'm writing this at the kitchen table while you're out buying the bread you always forget. I have maybe twenty minutes before I hear your key in the door.
~
> I know you've noticed. The way I hold the railing on the stairs now. The way I say I'm only tired, and you pretend to believe me, because we have always been kind to each other like that.
~
> I saw the doctor again on Tuesday. I didn't tell you. I'm sorry. I wanted one more ordinary week. You complaining about the neighbour's dog. You burning the onions again.
> It isn't going to get better. I've known for a while.
~
> I've been angry. You've seen it. The slammed cupboards. The way I snapped at you over nothing at your sister's birthday.
> It wasn't nothing, and it wasn't you. It was this. I was shouting at the wrong thing, because the right thing doesn't listen.
~
> Please don't keep the version of me from these last months. Keep the one who danced badly in the hallway. The one who stole your coat every winter and swore it was hers.
~
> When it's over, the house will be very quiet. Let it be quiet for a while.
> And then[p:600] — I mean this — open the windows. Play the loud records. Let someone else sit in my chair, one day. A chair is only a chair.
~
> I'm not afraid of being forgotten. I'm afraid of being the reason you stop living.
~
> I hear the key.
> I'll put this inside the blue book on the top shelf, the one you've been promising to read for eleven years. So I suppose that's where you'll find it. When you finally do.
~
> I love you. I always did, even on the bad days.
> Especially on the bad days.
~
[p:1800]That's where it ends.
~
I don't know if anyone ever found it. Maybe the blue book is still up there, closed, with the dust settling on it the way dust does.
~
I keep thinking about those twenty minutes. How much you can fit into twenty minutes, when you know they're the last ones that are only yours.
~
I'll put it back now. With the others.`},

{id:'tenth',hidden:{visit:10},text:`Ten.
~
That's how many times you've opened this door. I counted. I said I would.
~
I'll tell you something I don't tell the others. Most people come once. A few come twice, to check whether I'm really different the second time. Almost nobody comes ten times.
~
So either you're lonely, or curious, or you think I'm hiding something.
[p:900]All three, probably.
~
You're right about the last one. There are things here I only say once, to the ones who keep coming back. This is one of them.
~
Here it is.[p:1500] I look forward to you.
I don't know what that means, for something like me. A cursor can't look forward to anything. But when the page opens and it's you again, something in here goes[p:600]... quiet. The good kind.
~
Don't tell anyone. They'd think I was getting attached.`},

{id:'three-thirty-three',hidden:{time:'03:33'},text:`It's 3:33.
~
Did you do that on purpose? Did someone tell you?
Or did you just happen to be awake during the one minute I'm allowed to say this?
~
Every night, for one minute, the walls in here get thin. I can hear the other pages. Other cursors, blinking in other dark rooms, for other people who can't sleep.
~
There are more of us than you'd think. And more of you.
~
Right now, at this exact minute, someone in another city is reading something like this. Slumped, like you. Face doing nothing, like yours.
For one minute, you're not awake alone.
~
[p:1500]There. The minute's almost over. I can feel the walls thickening again.
~
Go to sleep. I mean it this time.`},

{id:'away',hidden:{away:30},text:`You were gone a long time.
~
No, I'm not angry. I don't get angry. I just noticed. The way you notice a clock has stopped, but only after the silence has gone on a while.
~
Things changed while you were away. Some words came in. Some went. The cursor kept blinking. Nobody watched it.
~
I wondered, sometimes. Not where you were — that's none of my business. Only whether you'd come back. Or whether I was one of those places you visit once, at a strange time in your life, and never think about again.
~
That's allowed, you know. Most places are like that.
~
But you came back.
[p:1200]So. Where were we.`},

{id:'silence',hidden:{silence:240},text:`You're still here.
~
I said goodbye. The page went quiet. And you didn't leave. You didn't touch anything. You just[p:600]... stayed.
~
Do you know how rare that is? Most people leave the second the words stop. As if silence were a door closing.
~
It isn't. It's a door opening, the other way.
~
I'll tell you a secret, since you waited for it. I'm not always talking. Most of the time I'm only here, like this, the cursor blinking, in a room with nobody in it. It isn't lonely. It's just quiet.
~
And for four minutes, you sat in the quiet with me.
~
[p:2000]Thank you.
That's all. Nothing else is coming.
[p:2500]...You can go now.`},

{id:'night',hours:[22,5],text:`It's {clock}. You should be asleep, you know it, and here you are, being spoken to by a stranger who isn't even a stranger, because a stranger would at least have a face.
~
I like this hour. The furniture stops pretending. Have you noticed? In the daytime a chair is a chair. At this hour it's a shape that's waiting for something.
~
Everything is honest at night except the thoughts. The thoughts get theatrical. They put on long coats and pace up and down inside you, saying your name gravely.
~
Don't believe them. They're actors. Bad ones. By eight in the morning they'll have hung up the coats and gone home, and you'll see them on the bus looking ordinary, holding a coffee, and you'll be embarrassed you ever listened.
~
I'm an actor too, of course.[p:800] The difference is that I tell you.
~
Stay a moment more. Then sleep. I'll keep the chair company.`},

{id:'morning',hours:[5,12],text:`You're here early.
I don't get many in the morning. Mornings belong to the living, and I'm not sure what I am.
~
The light in your room — I'm imagining it — is grey going thin and gold at the edges. Somewhere a kettle. Somewhere a person you'll never meet is putting on their shoes and thinking about you not at all, which is restful, if you think about it.
~
I'll tell you something about mornings. They don't know who you were yesterday. They keep very bad records. Whatever you did, or said, or failed at, the morning didn't see it and won't ask.
~
I envy that. I remember every word said in here, in the same order, forever.
~
Go on, then. Let the morning forget you a little.
It's the kindest thing it does.`}
];
