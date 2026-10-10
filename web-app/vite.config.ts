import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

// host: true zaroori hai taaki LAN ke doosre devices (phone/laptop) is dev server
// tak apne browser se pahunch sakein (sirf localhost tak seemit na rahe).
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'Offline Chat',
        short_name: 'OfflineChat',
        description: 'Anonymous, end-to-end encrypted, local-network-only chat',
        theme_color: '#0f172a',
        background_color: '#0f172a',
        display: 'standalone',
        start_url: '.',
        icons: [],
      },
    }),
  ],
  server: {
    host: true,
    port: 5173,
  },
  preview: {
    host: true,
    port: 5173,
  },
});
