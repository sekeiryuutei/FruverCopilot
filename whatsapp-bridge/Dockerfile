FROM node:20-alpine

WORKDIR /app

RUN apk add --no-cache ffmpeg git

COPY package.json ./
RUN npm install --omit=dev

COPY src ./src

ENV PORT=3000
ENV AUTH_DIR=/app/auth_info

EXPOSE 3000

CMD ["node", "src/index.js"]
