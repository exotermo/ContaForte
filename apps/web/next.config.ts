import type { NextConfig } from "next";

// Cabeçalhos X-Content-Type-Options, X-Frame-Options e Referrer-Policy são do gateway (nginx), para não duplicar.
// Baseline de segurança. CSP com nonce fica para um endurecimento posterior (Next injeta scripts inline).
const csp = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  "connect-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join("; ");

const config: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  // O app não usa next/image. Ligado, /_next/image atende sem login e carrega o sharp, que gasta memória e exige um
  // processador com SSE4.2 (o servidor não tem). O Dockerfile também tira o sharp da imagem.
  images: { unoptimized: true },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy", value: csp },
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
        ],
      },
    ];
  },
};

export default config;
