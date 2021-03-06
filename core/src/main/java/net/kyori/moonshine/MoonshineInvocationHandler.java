//
// moonshine - A localisation library for Java.
// Copyright (C) 2021 Mariell Hoversholm
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published
// by the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.
//

package net.kyori.moonshine;

import io.leangen.geantyref.GenericTypeReflector;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.moonshine.annotation.meta.ThreadSafe;
import net.kyori.moonshine.exception.MissingMoonshineMethodMappingException;
import net.kyori.moonshine.internal.IFindMethod;
import net.kyori.moonshine.internal.jre8.Java8FindMethod;
import net.kyori.moonshine.internal.jre9.Java9FindMethod;
import net.kyori.moonshine.model.MoonshineMethod;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The {@link InvocationHandler} for a {@link Moonshine}-driven {@link Proxy}.
 *
 * @param <R> the receiver type
 * @param <I> the intermediate message type
 * @param <F> the finalised placeholder type, post-resolving
 */
@ThreadSafe
/* package-private */ final class MoonshineInvocationHandler<R, I, O, F> implements InvocationHandler {
  /**
   * An empty array to substitute a state of missing method arguments.
   */
  private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

  /**
   * Utility to find {@link MethodHandle}s in
   */
  private static final IFindMethod FIND_METHOD_UTIL;

  private final Moonshine<R, I, O, F> moonshine;

  MoonshineInvocationHandler(final Moonshine<R, I, O, F> moonshine) {
    this.moonshine = moonshine;
  }

  @Override
  public @Nullable Object invoke(final Object proxy, final Method method, @Nullable Object @Nullable [] args)
      throws Throwable {
    // First we need to ensure this is not one of the _required_ implemented methods, as that would
    //   cause other exceptions later down the line and break expected behaviour of Java objects.
    if (method.getName().equals("equals")
        && method.getParameterCount() == 1
        && method.getReturnType() == boolean.class) {
      return args != null
          && args.length > 0
          && this.moonshine.equals(args[0]);
    } else if (method.getName().equals("hashCode")
        && method.getParameterCount() == 0
        && method.getReturnType() == int.class) {
      return this.moonshine.hashCode();
    } else if (method.getName().equals("toString")
        && method.getParameterCount() == 0
        && method.getReturnType() == String.class) {
      return GenericTypeReflector.getTypeName(this.moonshine.proxiedType().getType()) + '@' + this.moonshine.hashCode();
    }

    // With that out of the way, get rid of nulls in our parameters.
    // We do not want a null array as that becomes inconvenient to us.
    if (args == null) {
      // As an empty array is immutable, there's also no reason not to just cache and reuse it.
      args = EMPTY_OBJECT_ARRAY;
    }

    // We have nothing to do if the user has specified a default implementation...
    if (method.isDefault()) {
      // ... in which case, find it and invoke it appropriately.
      final MethodHandle handle = FIND_METHOD_UTIL.findMethod(method, proxy);
      if (args.length == 0) {
        return handle.invokeExact(proxy);
      } else {
        return handle.invokeWithArguments(args);
      }
    }

    // If for some reason the user wants to access the Moonshine instance, we will let them do so,
    //   though it is not advised.
    if (method.getReturnType() == Moonshine.class) {
      return this.moonshine;
    }

    final @Nullable MoonshineMethod<? extends R> moonshineMethod =
        this.moonshine.scannedMethods().get(method);
    if (moonshineMethod == null) {
      // This is illegal state, and should be reported if encountered.
      throw new MissingMoonshineMethodMappingException(this.moonshine.proxiedType().getType(), method);
    }

    final R receiver = moonshineMethod.receiverLocator().locate(method, proxy, args);
    final I intermediateMessage = this.moonshine.messageSource().messageOf(receiver, moonshineMethod.messageKey());
    final Map<String, ? extends F> resolvedPlaceholders = this.moonshine.placeholderResolverStrategy()
        .resolvePlaceholders(
            this.moonshine, receiver, intermediateMessage, moonshineMethod, args);
    final O renderedMessage = this.moonshine.messageRenderer().render(receiver, intermediateMessage,
        resolvedPlaceholders, method, this.moonshine.proxiedType().getType());

    if (method.getReturnType() == void.class) {
      this.moonshine.messageSender().send(receiver, renderedMessage);
      return null;
    } else {
      return renderedMessage;
    }
  }

  static {
    boolean isJava9 = false;
    try {
      Optional.class.getDeclaredMethod("ifPresentOrElse", Consumer.class, Runnable.class);
      isJava9 = true;
    } catch (final NoSuchMethodException ignored) {
      // This is not Java 9; nothing to do.
    }
    if (isJava9) {
      FIND_METHOD_UTIL = new Java9FindMethod();
    } else {
      FIND_METHOD_UTIL = new Java8FindMethod();
    }
  }
}
