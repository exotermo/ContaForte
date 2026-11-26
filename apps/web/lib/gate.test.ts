import { describe, expect, it } from "vitest";
import { BusyError, createGate } from "./gate";

const later = <T>(ms: number, value: T) => new Promise<T>((r) => setTimeout(() => r(value), ms));

describe("createGate", () => {
  it("nunca passa do limite de tarefas simultâneas e todas terminam", async () => {
    const run = createGate(2, 20, 5000);
    let inside = 0;
    let peak = 0;
    const results = await Promise.all(
      Array.from({ length: 12 }, (_, i) =>
        run(async () => {
          peak = Math.max(peak, ++inside);
          await later(5, null);
          inside -= 1;
          return i;
        }),
      ),
    );
    expect(results).toEqual(Array.from({ length: 12 }, (_, i) => i));
    expect(peak).toBe(2);
  });

  it("recusa quem não cabe na fila, sem começar a tarefa", async () => {
    const run = createGate(1, 1, 5000);
    let started = 0;
    const task = () => {
      started += 1;
      return later(20, "ok");
    };
    const first = run(task);
    const queued = run(task);
    await expect(run(task)).rejects.toBeInstanceOf(BusyError);
    await expect(Promise.all([first, queued])).resolves.toEqual(["ok", "ok"]);
    expect(started).toBe(2);
  });

  it("quem espera demais desiste e não rouba a vaga de quem vem depois", async () => {
    const run = createGate(1, 5, 10);
    const slow = run(() => later(50, "lenta"));
    let ran = false;
    await expect(run(async () => (ran = true))).rejects.toBeInstanceOf(BusyError);
    await expect(slow).resolves.toBe("lenta");
    await expect(run(async () => "depois")).resolves.toBe("depois");
    expect(ran).toBe(false);
  });

  it("uma tarefa com erro devolve a vaga", async () => {
    const run = createGate(1, 0, 10);
    await expect(run(() => Promise.reject(new Error("falhou")))).rejects.toThrow("falhou");
    await expect(run(async () => "ok")).resolves.toBe("ok");
  });
});
