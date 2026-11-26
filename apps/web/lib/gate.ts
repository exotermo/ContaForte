// Limita quantas tarefas que gastam muita memória rodam ao mesmo tempo no servidor do front (ADR-021).

export class BusyError extends Error {}

/**
 * No máximo `concurrency` tarefas ao mesmo tempo; até `maxQueue` esperam a vez, por no máximo `maxWaitMs`.
 * Quem não cabe na fila, ou espera demais, recebe BusyError sem que a tarefa comece.
 */
export function createGate(concurrency: number, maxQueue: number, maxWaitMs: number) {
  let running = 0;
  const queue: (() => void)[] = [];

  function acquire(): Promise<void> {
    if (running < concurrency) {
      running += 1;
      return Promise.resolve();
    }
    if (queue.length >= maxQueue) return Promise.reject(new BusyError());
    return new Promise((resolve, reject) => {
      const turn = () => {
        clearTimeout(timer);
        resolve();
      };
      const timer = setTimeout(() => {
        queue.splice(queue.indexOf(turn), 1);
        reject(new BusyError());
      }, maxWaitMs);
      queue.push(turn);
    });
  }

  function release() {
    const next = queue.shift();
    if (next) next(); // a vaga passa direto para o próximo: `running` não muda
    else running -= 1;
  }

  return async function run<T>(task: () => Promise<T>): Promise<T> {
    await acquire();
    try {
      return await task();
    } finally {
      release();
    }
  };
}
