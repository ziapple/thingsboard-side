#!/bin/bash
if [ $1 == "pub" ];then
  node ./node_modules/mqtt/bin/pub.js ${@:2}
elif [ $1 == "sub" ];then
  node ./node_modules/mqtt/bin/sub.js ${@:1}
fi
