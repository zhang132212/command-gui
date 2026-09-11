#!/usr/bin/env node
/**
 * 内嵌服务端分支冒烟测试（单人模式 / 集成服务器回归）
 *
 * 背景
 * ----
 * command-gui 客户端 jar 内置一份 com.remrin.server.*。当实例里没有安装
 * command-gui-server 时，由 CommandGUI.onInitialize() 走“内嵌分支”直接初始化服务端
 * 逻辑（单人模式、局域网主机都属于这种情形）。
 *
 * 这条分支必须在【模组初始化阶段】完成：MachineMod.init() 里的
 * CommandRegistrationCallback 只在 Commands 构造时生效，而 Commands 属于世界数据包
 * 资源（WorldOpenFlows -> WorldLoader -> ReloadableServerResources），在
 * ServerLifecycleEvents.SERVER_STARTING 之前就已经建好。一旦把 init() 推迟到
 * SERVER_STARTING，/machineadmin 与 /cgtest 会静默丢失——必须先执行一次 /reload
 * 重建 Commands 才会出现。
 *
 * 本脚本做的事
 * ------------
 * 用 `:runServer` 启动一个只加载 command-gui 的专用服务端（等价于单人模式的集成
 * 服务端侧），通过控制台执行 help，断言 /machineadmin 与 /cgtest 在【首次加载】就
 * 已注册（脚本不会执行 /reload）。同时校验测试环境本身有效：
 *   - command-gui 已加载，且 command-gui-server 未加载（否则会掩盖问题）；
 *   - 控制台输入可用（先发 say 探针）。
 *
 * 用法
 * ----
 *   node testing/smoke-embedded-server.mjs [--offline] [--timeout 300] [--keep]
 *
 *   --offline   给 gradle 传 --offline（依赖已缓存、无网络时使用）
 *   --timeout N 等待服务器启动的秒数，默认 300
 *   --keep      保留测试世界与 run/ 目录，便于排查
 *
 * 退出码：0 = 通过；1 = 失败或环境不满足。
 * 完整服务端日志：build/smoke-embedded-server/server.log
 */
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(fileURLToPath(new URL('../', import.meta.url)));
const reportDir = path.join(root, 'build', 'smoke-embedded-server');
const logFile = path.join(reportDir, 'server.log');
const runDir = path.join(root, 'run');
const LEVEL_NAME = 'smoke-test-world';
const REQUIRED_MOD = 'command-gui';
const FORBIDDEN_MOD = 'command-gui-server';
const PROBE = 'SMOKE_PROBE_OK';
const BACKUP_FILES = ['eula.txt', 'server.properties'];

const options = { offline: false, keep: false, timeoutMs: 300_000 };

function parseArgs(argv) {
   for (let i = 0; i < argv.length; i++) {
      const arg = argv[i];
      if (arg === '--offline') options.offline = true;
      else if (arg === '--keep') options.keep = true;
      else if (arg === '--timeout') options.timeoutMs = Number(argv[++i]) * 1000;
      else if (arg === '--help' || arg === '-h') {
         console.log('用法: node testing/smoke-embedded-server.mjs [--offline] [--timeout 秒数] [--keep]');
         process.exit(0);
      } else throw new Error(`未知参数: ${arg}`);
   }
   if (!Number.isFinite(options.timeoutMs) || options.timeoutMs <= 0) throw new Error('--timeout 需要正整数秒数');
}

const say = (message) => console.log(`[smoke] ${message}`);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function which(command) {
   const result = spawnSync(process.platform === 'win32' ? 'where' : 'which', [command], { encoding: 'utf8' });
   if (result.status !== 0) return null;
   return result.stdout.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)[0] ?? null;
}

/** 与 testing/run-tests.ps1 一致：从 JAVA_HOME / PATH / Program Files 里挑一个 JDK 25+。 */
function findJdkHome() {
   const candidates = [];
   const add = (dir) => { if (dir && !candidates.includes(dir)) candidates.push(dir); };
   add(process.env.JAVA_HOME);
   const javac = which('javac');
   if (javac) add(path.dirname(path.dirname(javac)));
   const programFiles = process.env['ProgramFiles'] ?? 'C:\\Program Files';
   for (const vendor of ['Microsoft', 'Eclipse Adoptium', 'Java']) {
      const vendorDir = path.join(programFiles, vendor);
      if (!fs.existsSync(vendorDir)) continue;
      for (const entry of fs.readdirSync(vendorDir)) {
         if (/jdk/i.test(entry)) add(path.join(vendorDir, entry));
      }
   }
   for (const candidate of candidates) {
      const javacPath = path.join(candidate, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac');
      if (!fs.existsSync(javacPath)) continue;
      const result = spawnSync(javacPath, ['-version'], { encoding: 'utf8' });
      const match = /javac (\d+)/.exec(`${result.stdout ?? ''}${result.stderr ?? ''}`);
      if (match && Number(match[1]) >= 25) {
         return { home: candidate, reported: `${result.stdout ?? result.stderr ?? ''}`.trim() };
      }
   }
   return null;
}

function killTree(pid) {
   if (!pid) return;
   if (process.platform === 'win32') {
      spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore' });
   } else {
      try {
         process.kill(-pid, 'SIGKILL');
      } catch {
         try { process.kill(pid, 'SIGKILL'); } catch { /* 已退出 */ }
      }
   }
}

async function main() {
   parseArgs(process.argv.slice(2));

   if (!fs.existsSync(path.join(root, 'settings.gradle'))) {
      throw new Error(`无法定位项目根目录（缺少 settings.gradle）: ${root}`);
   }
   const jdk = findJdkHome();
   if (!jdk) throw new Error('未找到 JDK 25+，请安装并把 JAVA_HOME 指向 JDK 25（与 testing/run-tests.ps1 要求一致）');
   say(`JDK: ${jdk.reported} (${jdk.home})`);

   fs.mkdirSync(reportDir, { recursive: true });
   const runDirExisted = fs.existsSync(runDir);
   fs.mkdirSync(runDir, { recursive: true });
   const backups = new Map();
   for (const name of BACKUP_FILES) {
      const file = path.join(runDir, name);
      backups.set(name, fs.existsSync(file) ? fs.readFileSync(file) : null);
   }
   fs.writeFileSync(path.join(runDir, 'eula.txt'), 'eula=true\n');
   fs.writeFileSync(path.join(runDir, 'server.properties'), [
      'level-type=minecraft\\:flat',
      `level-name=${LEVEL_NAME}`,
      'server-port=0',
      'online-mode=false',
      'max-tick-time=-1',
      'spawn-protection=0',
      'view-distance=4',
      'simulation-distance=4',
      'sync-chunk-writes=false',
      'difficulty=peaceful',
      'gamemode=creative',
      'function-permission-level=4',
      ''
   ].join('\n'));
   fs.rmSync(path.join(runDir, LEVEL_NAME), { recursive: true, force: true });

   const windows = process.platform === 'win32';
   const gradleArguments = [':runServer', '--console=plain', '-x', 'bumpVersion'];
   if (options.offline) gradleArguments.push('--offline');
   const executable = windows ? (process.env.ComSpec || 'cmd.exe') : 'bash';
   const cliArguments = windows
      ? ['/d', '/c', `gradlew.bat ${gradleArguments.join(' ')}`]
      : ['./gradlew', ...gradleArguments];
   say(`启动测试服务器: ${gradleArguments.join(' ')}（只加载 ${REQUIRED_MOD} 的专用服务端）`);

   let output = '';
   const child = spawn(executable, cliArguments, {
      cwd: root,
      env: { ...process.env, JAVA_HOME: jdk.home },
      stdio: ['pipe', 'pipe', 'pipe'],
      detached: !windows
   });
   child.stdout.on('data', (chunk) => { const text = chunk.toString('utf8'); output += text; process.stdout.write(text); });
   child.stderr.on('data', (chunk) => { const text = chunk.toString('utf8'); output += text; process.stderr.write(text); });
   child.on('error', (error) => { output += `\n[spawn error] ${error.message}\n`; });

   const send = (line) => {
      try { child.stdin.write(`${line}\n`); } catch { /* 进程已退出 */ }
   };
   const waitFor = async (pattern, timeoutMs) => {
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
         if (pattern.test(output)) return true;
         if (child.exitCode !== null) return false;
         await sleep(200);
      }
      return false;
   };
   const waitForExit = async (timeoutMs) => {
      if (child.exitCode !== null) return true;
      return Promise.race([
         new Promise((resolve) => child.once('exit', () => resolve(true))),
         sleep(timeoutMs).then(() => false)
      ]);
   };

   const problems = [];
   try {
      if (!await waitFor(/Done \(/, options.timeoutMs)) {
         problems.push(`服务器未在 ${Math.round(options.timeoutMs / 1000)}s 内启动完成`);
      } else {
         say('服务器已启动，开始校验测试环境与命令注册');

         if (!/^\t- command-gui /m.test(output)) problems.push(`测试环境无效：日志里没有加载 ${REQUIRED_MOD}`);
         if (/^\t- command-gui-server /m.test(output)) {
            problems.push(`测试环境无效：${FORBIDDEN_MOD} 也被加载了，无法验证内嵌分支`);
         }

         send(`say ${PROBE}`);
         if (!await waitFor(new RegExp(PROBE), 10_000)) {
            problems.push('无法通过控制台与服务器交互（say 探针没有回显）');
         }

         if (problems.length === 0) {
            // 关键断言：首次加载即注册。脚本不会执行 /reload。
            const registered = () => ({ admin: /\/machineadmin/.test(output), cgtest: /\/cgtest/.test(output) });
            send('help');
            await sleep(3_000);
            let found = registered();
            if (!found.admin || !found.cgtest) {
               send('help 2');
               await sleep(3_000);
               found = registered();
            }
            if (!found.admin) problems.push('/machineadmin 未注册：命令调度器里找不到该命令');
            if (!found.cgtest) problems.push('/cgtest 未注册：命令调度器里找不到该命令');
         }
      }
   } finally {
      send('stop');
      if (!await waitForExit(60_000)) {
         problems.push('stop 后服务器未在 60s 内退出，已强制结束');
         killTree(child.pid);
      }
      fs.mkdirSync(reportDir, { recursive: true });
      fs.writeFileSync(logFile, output);
      for (const [name, content] of backups) {
         const file = path.join(runDir, name);
         if (content === null) fs.rmSync(file, { force: true });
         else fs.writeFileSync(file, content);
      }
      if (!options.keep) {
         fs.rmSync(path.join(runDir, LEVEL_NAME), { recursive: true, force: true });
         if (!runDirExisted) fs.rmSync(runDir, { recursive: true, force: true });
      }
   }

   if (problems.length > 0) {
      console.error('');
      console.error('[smoke] 失败：');
      for (const problem of problems) console.error(`  x ${problem}`);
      console.error('');
      console.error('  这条断言保护的是“内嵌服务端分支必须在模组初始化阶段完成”这一约束：');
      console.error('  MachineMod.init() 里的 CommandRegistrationCallback 只在 Commands 构造时生效，');
      console.error('  而 Commands 早于 ServerLifecycleEvents.SERVER_STARTING 就已建好（见 CommandGUI.java 注释）。');
      console.error(`  完整日志: ${logFile}`);
      console.error('');
      console.error('[smoke] 日志尾部：');
      console.error(output.trimEnd().split(/\r?\n/).slice(-30).join('\n'));
      return 1;
   }

   say('通过：/machineadmin 与 /cgtest 在首次加载时均已注册（未执行 /reload）');
   say(`完整日志: ${logFile}`);
   return 0;
}

process.exitCode = await main().catch((error) => {
   console.error(`[smoke] 出错：${error.message}`);
   return 1;
});
