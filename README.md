# jepsen.etcdemo

Learn the Jepsen usage from the tutorial: https://github.com/jepsen-io/jepsen/tree/main/doc/tutorial

You can get history from `git log` to see the code changes, especially the chapter 8: `08-set`  

## Preparations

```clj
# May takes few hours, check without any exceptions
nohup ./up --dev &

# check jepsen_control and jepsen-n1 to jepsen-n5 build successfully
docker ps --all

```


## Usage

```clj
Usages: 

-q "Use quorum reads, instead of reading from any primary"
--test-count "run this test a mutiple times"
--time-limit "Excluding setup and teardown, how long should a test run for, in seconds"
-r "Approximate number of requests per second, per thread."
--ops-per-key "Maximum number of operations on any given key."
--concurrency "How many workers should we run. Must be an integer, optionally followed by n (e.g. 3n) to multiply by the number of nodes."
-w "workload. register or set"

lein run test --test-count 10 --concurrency 10 -r 50 -q -w register
Everything looks good! ヽ(‘ー`)ノ

lein run test --test-count 10 --concurrency 10 -r 50 -w register
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻

lein run test --time-limit 60 --test-count 2 --concurrency 10 -r 50 -q -w register
Everything looks good! ヽ(‘ー`)ノ

lein run test --time-limit 60 --test-count 2 --concurrency 10 -r 50 -w register
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻

# set workload
lein run test --time-limit 60 --test-count 2 --concurrency 10 --ops-per-key 10 -r 50 -w set
```

## F&A

1. If you're in the China, you cannot access to the images for the Docker Official Hub now(2024-12).
You should find your own images source, and edit `vim /etc/docker/daemon.json`.
more details in this [bolg](https://cloud.tencent.com/developer/article/1857227)

2. `nohup ./up --dev &` may spend lots of hours if your machine is not powerful. Be patient to wait!
2Core 2CPU spent me 18 hours to build the jepsen infra. Fuck my poverty

3. If your iptables version is `nftables`, you will get some exceptions

```clj
WARN [2025-01-22 06:58:08,518] jepsen worker nemesis - jepsen.generator.interpreter Process :nemesis crashed
clojure.lang.ExceptionInfo: Command exited with non-zero status 4 on node n5:
sudo -k -S -u root bash -c "cd /; iptables -A INPUT -s 172.18.0.3,172.18.0.4 -j DROP -w"
STDERR:
iptables v1.8.9 (nf_tables):  TABLE_ADD failed (No such file or directory): table filter
```
It's not harmful to test(I guess), you can do the followings to escape from this
```clj
# execute in jepsen-n1 to jepsen-n5 docker containers

update-alternatives --set iptables /usr/sbin/iptables-legacy
iptables -L -n -v
iptables --version
```

4. Use the latest release of jepsen and clojure, otherwise you'll get some unexpected exceptions

```clj
   :dependencies [[org.clojure/clojure "1.11.3"]
   [jepsen "0.3.5"]
   [verschlimmbesserung "0.1.3"]])
```   


## License

Copyright © 2025

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
