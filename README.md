# TextSling

An Android app that forwards you messages from one phone number to another via SMS/RCS, and alternatively, an email should you so desire.

Why did I make this? Well, I happened to get an alternate phone which I've designated as a backup with its own phone line, but it's very tedious to have to log into every service my main phone line is registered with, that is sent OTPs to, for authentication/logging in. I got too lazy to have to look on one phone and enter it in the other.

I did not want to mass forward all texts my main phone received indiscriminately to my backup, and my service provider has no option for intercepting and forwarding only OTPs in specific.

There seemed to be certain apps on the play store that technically _could_ do this, but I did not want to trust anyone with security codes/access to my messages, so I decided to make an app myself.

The OTP extraction mechanism is mostly inspired by JATIN's SecureOTP, shoutout to [Jatin](https://github.com/26JATIN) and [SecureOTP](https://github.com/26JATIN/SecureOTP).

Here's some features that the app can currently do:

1) Intercept ONLY your OTPs, or all texts should you so desire.
2) Choose between another number recipient, or email, or both! (emails require you to set up app passwords and your sender email needs to be Gmail, too lazy to add any other support. Your receiver can be anyone, however.)
3) Send test messages/emails to see if it's working correctly.
4) Consumes very little/no power. Event driven, so only does its job when notified by the Android system.

I have no (read: ZERO) app dev experience, being someone who deals with very different stuff, so had to write a bunch in Python, then have some LLM agents work their magic translating it to Kotlin et al. Very cool, Mr Altman.

UI was also concocted by the agent, and I iterated a bit to dial in what worked for me. It's simple, and gets the job done - nothing too fancy.

Some technical details summarised by the agent in [implementation_details.md](implementation_details.md).

Known issue - app icon doesn't scale properly. Will fix it sometime...

Feel free to use this code as you wish. If you somehow miraculously stumbled upon this repo, have any ideas for improvements, find bugs, or whatever, make a pull request or open an issue. I *MIGHT* get around to fixing them, but no promises.

Licensed under [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0).
