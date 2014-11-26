var exec = require('child_process').execFile;
var fs = require("fs");
var path = require('path');

var JAVA_PATH = exports.JAVA_PATH = "java";
var JAR_PATH = exports.JAR_PATH = 
	path.join(path.dirname(fs.realpathSync(__filename)), 'yjs.jar');

exports.run = function(args,callback) {
	args.unshift("-jar", JAR_PATH);
	callback = callback ? callback : function(error,stdout,stderr){
		console.log(stdout);
		console.error(stderr);
		if(error !== null)
			console.error("exec error: " + error);
	};
	child = exec(JAVA_PATH,args,callback);
	return child;
};
