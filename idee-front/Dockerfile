FROM node:22.23.2-bookworm-slim AS build
WORKDIR /app
COPY idee-front/package.json idee-front/package-lock.json idee-front/.npmrc ./
RUN npm ci
COPY idee-front/angular.json idee-front/tsconfig*.json ./
COPY idee-front/src ./src
RUN npm run build
FROM node:22.23.2-bookworm-slim AS ssr
WORKDIR /app
ENV NODE_ENV=production HOST=0.0.0.0 PORT=4000 TZ=Europe/Paris
COPY --from=build --chown=node:node /app/dist/idee ./dist/idee
USER node
EXPOSE 4000
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 CMD node -e "fetch('http://127.0.0.1:4000/_health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node", "dist/idee/server/server.mjs"]

FROM nginx:1.28-alpine AS web
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/idee/browser /usr/share/nginx/html
RUN chmod -R a+rX /usr/share/nginx/html
EXPOSE 80
