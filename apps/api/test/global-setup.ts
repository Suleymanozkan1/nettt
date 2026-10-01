import { execSync } from 'node:child_process';

export default function setup(): void {
  const url = process.env.TEST_DATABASE_URL ?? 'postgresql://stage:stage@localhost:5432/stagestack_ci';
  execSync('npx prisma migrate deploy', { stdio: 'inherit', env: { ...process.env, DATABASE_URL: url } });
}
