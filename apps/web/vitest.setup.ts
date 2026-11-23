import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// sem `globals: true` o Testing Library não limpa o DOM sozinho entre os testes
afterEach(() => cleanup());
