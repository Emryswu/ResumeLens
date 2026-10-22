"use client";

import { Check, ChevronDown } from "lucide-react";
import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

export type ChoiceOption<T extends string> = {
  value: T;
  label: string;
  description: string;
};

/** A single-choice popup: keyboard focus follows the active option, selection stays controlled by the caller. */
export function ChoiceMenu<T extends string>({ label, value, options, disabled, onChange, children }: {
  label: string;
  value: T;
  options: readonly ChoiceOption<T>[];
  disabled?: boolean;
  onChange: (value: T) => void;
  children: ReactNode;
}) {
  const id = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const expanded = open && !disabled;

  useEffect(() => {
    if (expanded) itemRefs.current[activeIndex]?.focus();
  }, [expanded, activeIndex]);

  useEffect(() => {
    if (!expanded) return;
    const outside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", outside);
    return () => document.removeEventListener("pointerdown", outside);
  }, [expanded]);

  function show() {
    setActiveIndex(options.findIndex((option) => option.value === value));
    setOpen(true);
  }

  function close() {
    setOpen(false);
    triggerRef.current?.focus();
  }

  function choose(next: T) {
    onChange(next);
    close();
  }

  function navigate(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      close();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((index) => (index + (event.key === "ArrowDown" ? 1 : -1) + options.length) % options.length);
    } else if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      setActiveIndex(event.key === "Home" ? 0 : options.length - 1);
    } else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      choose(options[activeIndex].value);
    } else if (event.key === "Tab") {
      // Let the browser advance from the trigger to the next (or previous) form control.
      close();
    }
  }

  return (
    <div className="choice-menu" ref={rootRef}>
      <button type="button" className="choice-menu-trigger" ref={triggerRef} disabled={disabled}
        aria-label={label} aria-haspopup="listbox" aria-expanded={expanded}
        aria-controls={expanded ? id : undefined}
        onClick={() => expanded ? close() : show()}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            show();
          }
        }}>
        {children}
        <ChevronDown size={14} className="choice-menu-chevron" aria-hidden="true" />
      </button>
      {expanded && (
        <div id={id} className="choice-menu-listbox" role="listbox" aria-label={label} onKeyDown={navigate}>
          {options.map((option, index) => (
            <button key={option.value} type="button" className="choice-menu-option" role="option"
              ref={(element) => { itemRefs.current[index] = element; }}
              aria-selected={option.value === value} aria-labelledby={`${id}-${index}-label`}
              aria-describedby={`${id}-${index}-description`} tabIndex={index === activeIndex ? 0 : -1}
              onFocus={() => setActiveIndex(index)} onClick={() => choose(option.value)}>
              <span className="choice-menu-option-copy">
                <span id={`${id}-${index}-label`} className="choice-menu-option-label">{option.label}</span>
                <span id={`${id}-${index}-description`} className="choice-menu-option-description">{option.description}</span>
              </span>
              {option.value === value && <Check size={17} strokeWidth={2.2} aria-hidden="true" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
