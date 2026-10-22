"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { HOME_PATH } from "../workspace-nav";

/** The assistant moved to the home page; old links to /assistant land there. */
export default function AssistantRedirect() {
  const router = useRouter();
  useEffect(() => {
    router.replace(HOME_PATH);
  }, [router]);
  return null;
}
