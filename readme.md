What is YetiScript?
-------------------
YetiScript compiles [yeti](http://mth.github.io/yeti/) code to JavaScript.  

Yeti is an ML-style functional programming language, with static type-inference  with tries to be clean and minimal expressive and to interface well with JavaScript code.

YetiScript tries to be as close to yeti as it can, however there are some [differences](https://github.com/chrisichris/yetiscript/wiki/Differences-to-Yeti).

Status
------
YetiScript is in early development and still very buggy, however it compiles nearly all of yeti-code.

Get Started
-----------
Java JDK7 is required. git-clone yetiscript and build it with ant.

    > git clone git://github.com/chrisichris/yetiscript.git
    > cd yetiscript
    > ant

The resulting `yjs.jar` contains everything needed to compile and evaluate yetiscript.

Use it to evaluate an expression:

    java -jar yjs.jar -e "1 + 1"
   
run an example using the build in rhino:

    java -jar yjs.jar -r examples/fact.yjs 

compile an example to `.build` 

    java -jar yjs.jar -d .build examples/fact.yjs

print help

    java -jar yjs.jar -h

Questions, Feedback
-------------------
Please point all your questions and feedback to the [yeti mailinglist](https://groups.google.com/forum/#!forum/yeti-lang).

Documentation
-------------
 - [tutorial](http://dot.planet.ee/yeti/intro.html) - tutorial for the original yeti language (note the differences doc)
 - [differences to yeti]( https://github.com/chrisichris/yetiscript/wiki/Differences-to-Yeti) - differences of yetiscript to yeti
 - [yeti std api](http://dot.planet.ee/yeti/docs/latest/yeti.lang.std.html) - api docs for the std api of yeti which is nearly identical to yetiscripts std
   is mostly supported)   
 - [std.yjs](https://github.com/chrisichris/yetiscript/blob/master/modules/std.yjs) : the source of the YetiScript std api
 - [yeti homepage](http://mth.github.io/yeti/) : the homepage of yeti with a lot more info 
 - [YetiScript wiki](https://github.com/chrisichris/yetiscript/wiki): feel free to add

Credits
-------

YetiScript is a fork of [yeti](http://mth.github.io/yeti/) by Madis Janson. 
YetiScript adds a JavaScript backend to the yeti compiler - which does the 
bulk of the work.  
