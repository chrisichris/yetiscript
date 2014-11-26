What is YetiScript?
-------------------
YetiScript compiles [yeti](http://mth.github.io/yeti/) code to JavaScript.  

Yeti is an ML-style functional programming language, with static type-inference  with tries to be clean and minimal expressive and to interface well with JavaScript code.

YetiScript tries to be as close to yeti as it can, however there are some [differences](https://github.com/chrisichris/yetiscript/wiki/Differences-to-Yeti).

TOC
---
* [Status](#status)
* [Get Started](#get-started)
* [Basic Usage](#basic-usage)
* [Questions, Feedback](#questions-feedback)
* [Documentation](#documentation)
* [Credits](#credits)

## Status

YetiScript is in early development and still very buggy, however it compiles nearly all of yeti-code.

#### Update Version 0.2.1
* 26-11-2014 Chrome Extension using the build in compileserver as seperat project [YetiScriptConsole](https://github.com/chrisichris/yetiscript-console)
* 26-11-2014 fixed bug in npm module logging jar which node did not understand
* 25-11-2014 added webserver to use compiler/repl as local rest-api
use the -server swith on yjs to start a NanoHTTPD server wich compiles yetiscript as rest-service. 
* 21-11-2014 repl now works with rhino
* 21-11-2014 removed dependencie on lo-dash
The import was making trouble, so I went further to remove it all together 

#### Updates Version 0.1.1
* 20-11-2014 Published to npm 
YetiScript can be installed as a node.js module. The modulename is ``yetiscript`` the if installed globally it can be invoked with the ``yjs`` command.
* 20-11-2014 New Versioning Scheme and Version 0.1.1
Yetiscript uses the node.js versioning scheme. And starts all over new with Version 0.1.1. The version is in the std api ``yetiScriptVersion``. The version of yeti the current YetiScript is based on is in ``yetiVersion`` (0.9.9+) 
* 18-11-2014 Scoping like in yeti
Before YetiScript used the same local scope as JavaScript (function-scope). Within the same function body a name could only be bound once. Now shadowing, structure and functions-scoping is like in yeti. Bindings with the same name in ohne function get translated to javascript with appending numbers ie
    fn x = 
      (x = x + 2;
       x);
translates to
    var fn = function fn(x) {
      x1 = x + 2;
      return x1;
    } 

## Get Started

Java JDK7 is required an ``java`` must be on the path.
YetiScript itself is distributed as a npm-module. To install it execute the 
follwing commands
    
    //test jave version
    >java -version 
    java version "1.7.0....."

    //install yetiscript
    >npm install yetiscript -g
    ....

    //use yjs to run the yetiscript compiler
    >yjs
    help msg ....


Alternatively (and to use the lates version) you can git clone it and build it
with ant

    > git clone git://github.com/chrisichris/yetiscript.git
    > cd yetiscript
    > ant

The resulting ``yjs.jar`` contains everything needed and can be use like the
npm ``yjs`` command:

## Basic Usage

Evaluate an expression with node:

    $yjs -e "1 + 1" | node

evaluate an expression with rhino (build into JDK7):

    $yjs -r -e "1 + 1"
   
run an example using node:

    yjs examples/fact.yjs | node 

run an example using the build in rhino:

    yjs -r examples/fact.yjs 

compile an example to `build` directory 

    yjs -d build examples/fact.yjs

print help

    yjs yjs.jar -h

## Questions Feedback

Please point all your questions and feedback to the [yeti mailinglist](https://groups.google.com/forum/#!forum/yeti-lang).

## Documentation

 - [tutorial](http://dot.planet.ee/yeti/intro.html) - tutorial for the original yeti language (note the differences doc)
 - [differences to yeti]( https://github.com/chrisichris/yetiscript/wiki/Differences-to-Yeti) - differences of yetiscript to yeti
 - [yeti std api](http://dot.planet.ee/yeti/docs/latest/yeti.lang.std.html) - api docs for the std api of yeti which is nearly identical to yetiscripts std
   is mostly supported)   
 - [std.yjs](https://github.com/chrisichris/yetiscript/blob/master/modules/std.yjs) : the source of the YetiScript std api
 - [yeti homepage](http://mth.github.io/yeti/) : the homepage of yeti with a lot more info 
 - [YetiScript wiki](https://github.com/chrisichris/yetiscript/wiki): feel free to add

## Credits

YetiScript is a fork of [yeti](http://mth.github.io/yeti/) by Madis Janson. 
YetiScript adds a JavaScript backend to the yeti compiler - which does the 
bulk of the work. 

Uses the following apis:
- [nanohttpd](https://github.com/NanoHttpd/nanohttpd) (modified BSD license)
