// GET /api/volta: página de volta do checkout e do portal, com o botão que abre o OSTIE.
module.exports = (req, res) => {
  const ok = req.query && req.query.ok === '1';
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.status(200).send(`<!doctype html><html lang="pt-BR"><head>
<meta name="viewport" content="width=device-width,initial-scale=1"><title>OSTIE</title>
<style>body{font-family:sans-serif;background:#0b0b12;color:#eee;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0;padding:16px;text-align:center}
a{display:inline-block;margin-top:24px;padding:14px 28px;border-radius:24px;background:#6c5ce7;color:#fff;text-decoration:none;font-weight:600}</style>
</head><body><div><h1>${ok ? 'Pronto!' : 'Tudo bem'}</h1><p>${ok ? 'Sua assinatura do OSTIE foi registrada. Pode levar alguns segundos para liberar.' : 'Nada foi cobrado.'}</p>
<a href="ostie://assinatura">Voltar ao OSTIE</a></div></body></html>`);
};
